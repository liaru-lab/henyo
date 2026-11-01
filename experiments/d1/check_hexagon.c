// Synthetic Q4_K correctness probe; no captured model weights or UI data.
#include "ggml-alloc.h"
#include "ggml-backend.h"
#include "ggml.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
typedef struct ggml_tensor T;
static float randv(unsigned *s) {
  *s ^= *s << 13;
  *s ^= *s >> 17;
  *s ^= *s << 5;
  return ((int)(*s % 2001) - 1000) / 7919.137f;
}
static void fill(T *t, unsigned seed) {
  size_t n = ggml_nelements(t);
  float *x = malloc(n * 4);
  for (size_t i = 0; i < n; ++i)
    x[i] = randv(&seed) + (t->ne[1] == 1 ? 1.0f : 0.0f);
  if (t->type == GGML_TYPE_F32)
    ggml_backend_tensor_set(t, x, 0, n * 4);
  else {
    size_t nb = ggml_nbytes(t);
    void *q = malloc(nb);
    size_t written =
        ggml_quantize_chunk(t->type, x, q, 0, t->ne[1], t->ne[0], NULL);
    if (written != nb)
      abort();
    ggml_backend_tensor_set(t, q, 0, nb);
    free(q);
  }
  free(x);
}
static T *weight(struct ggml_context *c, int k, int m, enum ggml_type ty,
                 const char *name) {
  T *t = ggml_new_tensor_2d(c, ty, k, m);
  ggml_set_name(t, name);
  return t;
}
static float *compute(ggml_backend_t *backends, T **w, int n, int mode,
                      int *count) {
  setenv("HENYO_D1_PAIR_GLU", mode ? "1" : "0", 1);
  unsetenv("HENYO_D1_PAIR_MAX_TOKENS");
  unsetenv("HENYO_D1_HMX_M");
  unsetenv("HENYO_D1_HMX_N");
  unsetenv("HENYO_D1_HMX_SCOPE");
  if (mode == 2) {
    setenv("HENYO_D1_HMX_M", "512", 1);
    setenv("HENYO_D1_HMX_N", "128", 1);
    setenv("HENYO_D1_HMX_SCOPE", "gate", 1);
  }
  struct ggml_init_params p = {.mem_size = 8 * 1024 * 1024, .no_alloc = true};
  struct ggml_context *c = ggml_init(p);
  T *in = ggml_new_tensor_2d(c, GGML_TYPE_F32, 2048, n);
  ggml_set_input(in);
  T *g = ggml_mul_mat(c, w[0], in), *u = ggml_mul_mat(c, w[1], in),
    *out = ggml_swiglu_split(c, g, u);
  ggml_set_output(out);
  struct ggml_cgraph *graph = ggml_new_graph_custom(c, 4096, false);
  ggml_build_forward_expand(graph, out);
  ggml_backend_sched_t sched =
      ggml_backend_sched_new(backends, NULL, 2, 4096, false, true);
  if (!ggml_backend_sched_alloc_graph(sched, graph))
    abort();
  fill(in, 27182);
  if (ggml_backend_sched_graph_compute(sched, graph) != GGML_STATUS_SUCCESS)
    abort();
  *count = 7680 * n;
  float *v = malloc(*count * 4);
  ggml_backend_tensor_get(out, v, 0, *count * 4);
  ggml_backend_sched_free(sched);
  ggml_free(c);
  return v;
}
int main(int argc, char **argv) {
  if (argc != 3)
    return 2;
  setenv("GGML_HEXAGON_OPFUSION", "1", 1);
  setenv("GGML_HEXAGON_NHMX", "1", 1);
  ggml_backend_load_all_from_path(argv[1]);
  ggml_backend_load(argv[2]);
  ggml_backend_t backends[] = {ggml_backend_init_by_name("HTPT0", NULL),
                               ggml_backend_init_by_name("CPU", NULL)};
  if (!backends[0] || !backends[1])
    return 3;
  struct ggml_init_params p = {.mem_size = 1024 * 1024, .no_alloc = true};
  struct ggml_context *cw = ggml_init(p);
  T *w[2];
  w[0] = weight(cw, 2048, 7680, GGML_TYPE_Q4_K, "blk.0.ffn_gate.weight");
  w[1] = weight(cw, 2048, 7680, GGML_TYPE_Q4_K, "blk.0.ffn_up.weight");
  ggml_backend_buffer_t buf = ggml_backend_alloc_ctx_tensors(cw, backends[0]);
  if (!buf)
    return 4;
  ggml_backend_buffer_set_usage(buf, GGML_BACKEND_BUFFER_USAGE_WEIGHTS);
  for (int i = 0; i < 2; ++i)
    fill(w[i], 16180 + i * 179);
  int shapes[] = {1, 7, 32, 33, 63, 85, 256, 511, 512, 713, 1099, 1100};
  int all_exact = 1;
  for (int j = 0; j < 12; ++j) {
    int a, b;
    float *ref = compute(backends, w, shapes[j], 0, &a),
          *val = compute(backends, w, shapes[j], 2, &b);
    if (a != b)
      abort();
    size_t diff = 0;
    double maxerr = 0;
    for (int i = 0; i < a; ++i) {
      if (!isfinite(val[i]) || !isfinite(ref[i]))
        abort();
      if (memcmp(ref + i, val + i, 4))
        diff++;
      maxerr = fmax(maxerr, fabs((double)val[i] - ref[i]));
    }
    printf("tokens=%d elements=%d bit_different=%zu max_abs=%.10g\n", shapes[j],
           a, diff, maxerr);
    fflush(stdout);
    if (diff)
      all_exact = 0;
    free(ref);
    free(val);
  }
  printf("all_bitwise_exact=%d\n", all_exact);
  return all_exact ? 0 : 5;
}
