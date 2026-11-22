// hymt-serve: Hy-MT1.5 的常驻翻译进程（给 HexAnima 用）
//
// 用法: hymt-serve <model.gguf> [threads]
// 启动后打印 "ready"（stderr 打日志，stdout 只走协议），然后从 stdin 读命令、往 stdout 写结果：
//   T\t<base64 中文>      →  R\t<base64 英文>   或  E\t<原因>
//   S\t<base64 指令>      →  OK                 （设置 system 指令，空串=清除）
//   Q                     →  退出
// base64 是为了让换行/制表符/Unicode 都能安全过管道。
//
// 模板（从 GGUF 元数据读出，不能改特殊 token）：
//   <｜hy_begin▁of▁sentence｜>[system<｜hy_place▁holder▁no▁3｜>]<｜hy_User｜>Please translate to English:\n\n<原文><｜hy_Assistant｜>
#include "llama.h"
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>
#include <clocale>

static const char * BOS   = "<｜hy_begin▁of▁sentence｜>";
static const char * PH3   = "<｜hy_place▁holder▁no▁3｜>";
static const char * USER  = "<｜hy_User｜>";
static const char * ASSIS = "<｜hy_Assistant｜>";

static std::string g_system;
static llama_model * g_model = nullptr;
static llama_context * g_ctx = nullptr;
static const llama_vocab * g_vocab = nullptr;
static llama_sampler * g_smpl = nullptr;

static const char * B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static std::string b64_enc(const std::string & in) {
    std::string out;
    out.reserve((in.size() + 2) / 3 * 4);
    size_t i = 0;
    while (i + 2 < in.size()) {
        unsigned v = (unsigned char) in[i] << 16 | (unsigned char) in[i + 1] << 8 | (unsigned char) in[i + 2];
        out += B64[(v >> 18) & 63]; out += B64[(v >> 12) & 63]; out += B64[(v >> 6) & 63]; out += B64[v & 63];
        i += 3;
    }
    if (i + 1 == in.size()) {
        unsigned v = (unsigned char) in[i] << 16;
        out += B64[(v >> 18) & 63]; out += B64[(v >> 12) & 63]; out += "==";
    } else if (i + 2 == in.size()) {
        unsigned v = (unsigned char) in[i] << 16 | (unsigned char) in[i + 1] << 8;
        out += B64[(v >> 18) & 63]; out += B64[(v >> 12) & 63]; out += B64[(v >> 6) & 63]; out += '=';
    }
    return out;
}

static int b64_val(char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

static std::string b64_dec(const std::string & in) {
    std::string out;
    int buf = 0, bits = 0;
    for (char c : in) {
        int v = b64_val(c);
        if (v < 0) continue;
        buf = (buf << 6) | v; bits += 6;
        if (bits >= 8) { bits -= 8; out += (char) ((buf >> bits) & 0xFF); }
    }
    return out;
}

static bool translate(const std::string & zh, std::string & out, std::string & err) {
    std::string prompt = BOS;
    if (!g_system.empty()) { prompt += g_system; prompt += PH3; }
    prompt += USER;
    prompt += "Please translate to English:\n\n";
    prompt += zh;
    prompt += ASSIS;

    const int n_prompt = -llama_tokenize(g_vocab, prompt.c_str(), prompt.size(), nullptr, 0, false, true);
    if (n_prompt <= 0) { err = "tokenize failed"; return false; }
    std::vector<llama_token> toks(n_prompt);
    if (llama_tokenize(g_vocab, prompt.c_str(), prompt.size(), toks.data(), toks.size(), false, true) < 0) {
        err = "tokenize failed"; return false;
    }
    llama_memory_clear(llama_get_memory(g_ctx), true);

    llama_batch b = llama_batch_get_one(toks.data(), toks.size());
    if (llama_decode(g_ctx, b)) { err = "prompt decode failed"; return false; }

    out.clear();
    const int max_new = 256;
    llama_token id = 0;
    for (int n = 0; n < max_new; n++) {
        id = llama_sampler_sample(g_smpl, g_ctx, -1);
        if (llama_vocab_is_eog(g_vocab, id)) break;
        char buf[256];
        int len = llama_token_to_piece(g_vocab, id, buf, sizeof(buf), 0, true);
        if (len > 0) out.append(buf, len);
        b = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, b)) { err = "decode failed"; return false; }
    }
    return true;
}

static void nolog(enum ggml_log_level, const char *, void *) { }

int main(int argc, char ** argv) {
    std::setlocale(LC_NUMERIC, "C");
    if (argc < 2) { fprintf(stderr, "usage: %s <model.gguf> [threads]\n", argv[0]); return 2; }
    int nthr = argc > 2 ? atoi(argv[2]) : 4;
    if (nthr < 1) nthr = 4;

    llama_log_set(nolog, nullptr);
    ggml_backend_load_all();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(argv[1], mp);
    if (!g_model) { printf("E\tmodel load failed\n"); fflush(stdout); return 1; }
    g_vocab = llama_model_get_vocab(g_model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 1024;
    cp.n_batch = 1024;
    cp.n_threads = nthr;
    cp.n_threads_batch = nthr;
    cp.no_perf = true;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { printf("E\tcontext failed\n"); fflush(stdout); return 1; }

    auto sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    g_smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(g_smpl, llama_sampler_init_greedy());

    printf("ready\n");
    fflush(stdout);

    char * line = nullptr;
    size_t cap = 0;
    ssize_t got;
    while ((got = getline(&line, &cap, stdin)) > 0) {
        while (got > 0 && (line[got - 1] == '\n' || line[got - 1] == '\r')) line[--got] = 0;
        if (got == 0) continue;
        if (line[0] == 'Q') break;
        if (line[0] == 'S' && (line[1] == '\t' || line[1] == 0)) {
            g_system = got > 2 ? b64_dec(std::string(line + 2)) : std::string();
            printf("OK\n"); fflush(stdout);
            continue;
        }
        if (line[0] == 'T' && (line[1] == '\t' || line[1] == 0)) {
            std::string zh = got > 2 ? b64_dec(std::string(line + 2)) : std::string();
            std::string en, err;
            if (translate(zh, en, err)) { printf("R\t%s\n", b64_enc(en).c_str()); }
            else { printf("E\t%s\n", err.c_str()); }
            fflush(stdout);
            continue;
        }
        printf("E\tbad command\n"); fflush(stdout);
    }
    free(line);
    llama_sampler_free(g_smpl);
    llama_free(g_ctx);
    llama_model_free(g_model);
    return 0;
}
