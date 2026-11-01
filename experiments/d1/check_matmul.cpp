// Synthetic dispatch/regression check for the pinned ggml OpenCL backend.
// Compare the complete output file from unmodified and patched libraries.
#include "ggml.h"
#include "ggml-alloc.h"
#include "ggml-backend.h"
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <vector>

int main(int argc, char ** argv) {
    if (argc != 3) {
        std::fprintf(stderr, "usage: check-matmul DEVICE OUTPUT_FILE\n");
        return 2;
    }
    ggml_backend_load_all();
    auto device = ggml_backend_dev_by_name(argv[1]);
    if (!device || ggml_backend_dev_type(device) != GGML_BACKEND_DEVICE_TYPE_GPU) {
        return 3;
    }
    auto backend = ggml_backend_dev_init(device, nullptr);
    if (!backend) {
        return 4;
    }
    std::ofstream output(argv[2], std::ios::binary);
    if (!output) {
        ggml_backend_free(backend);
        return 5;
    }
    int shapes = 0;
    // N crosses GEMV, small-batch, DP4A and tile boundaries. M=65 exercises
    // fallback dispatch. Two passes change activations on live buffers. The
    // pinned backend expects quantized weights to be uploaded once per tensor.
    for (int m : {64, 65, 128}) {
        for (int n : {1, 2, 8, 9, 15, 16, 31, 32, 63, 64, 65, 85, 96, 896}) {
            const int k = 512;
            ggml_init_params params = {ggml_tensor_overhead() * 8 + ggml_graph_overhead_custom(8, false), nullptr, true};
            auto ctx = ggml_init(params);
            auto w = ggml_new_tensor_2d(ctx, GGML_TYPE_Q4_K, k, m);
            auto a = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, k, n);
            auto result = ggml_mul_mat(ctx, w, a);
            auto graph = ggml_new_graph_custom(ctx, 8, false);
            ggml_build_forward_expand(graph, result);
            auto buffer = ggml_backend_alloc_ctx_tensors(ctx, backend);
            if (!buffer) {
                ggml_free(ctx);
                ggml_backend_free(backend);
                return 6;
            }
            std::vector<float> weights(k * m), activations(k * n), values(m * n);
            std::vector<uint8_t> packed(ggml_nbytes(w));
            uint32_t seed = 42;
            auto random_value = [&]() {
                seed ^= seed << 13;
                seed ^= seed >> 17;
                seed ^= seed << 5;
                return (int(seed % 2001) - 1000) / 1000.0f;
            };
            for (auto & value : weights) value = random_value();
            ggml_quantize_chunk(GGML_TYPE_Q4_K, weights.data(), packed.data(), 0, m, k, nullptr);
            ggml_backend_tensor_set(w, packed.data(), 0, packed.size());
            for (int pass = 0; pass < 2; ++pass) {
                for (auto & value : activations) value = random_value();
                ggml_backend_tensor_set(a, activations.data(), 0, activations.size() * sizeof(float));
                auto status = ggml_backend_graph_compute(backend, graph);
                if (status != GGML_STATUS_SUCCESS) {
                    ggml_backend_buffer_free(buffer);
                    ggml_free(ctx);
                    ggml_backend_free(backend);
                    return 7;
                }
                ggml_backend_tensor_get(result, values.data(), 0, values.size() * sizeof(float));
                for (float value : values) {
                    if (!std::isfinite(value)) return 8;
                }
                output.write(reinterpret_cast<const char *>(values.data()), values.size() * sizeof(float));
            }
            ggml_backend_buffer_free(buffer);
            ggml_free(ctx);
            ++shapes;
        }
    }
    ggml_backend_free(backend);
    output.close();
    if (!output) return 9;
    std::printf("Completed %d shapes, two activation versions each\n", shapes);
}
