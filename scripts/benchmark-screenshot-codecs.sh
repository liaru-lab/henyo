#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
runtime_root="${TMPDIR:-/data/data/com.termux/files/usr/tmp}"
bench_dir="$(mktemp -d -p "$runtime_root" henyo-codec.XXXXXX)"
source_path=""

cleanup() {
    if [[ -n "$source_path" && "$source_path" == "$runtime_root"/henyo/screens/codec-benchmark-* ]]; then
        rm -f -- "$source_path"
    fi
    if [[ "$bench_dir" == "$runtime_root"/henyo-codec.* ]]; then
        rm -rf -- "$bench_dir"
    fi
}
trap cleanup EXIT

median_ms() {
    python -c 'import statistics,sys; values=[int(x) for x in sys.argv[1].split(",") if x]; print(f"{statistics.median(values)/1000:.1f}")' "$1"
}

metric() {
    local filter="$1"
    local pattern="$2"
    local source="$3"
    local encoded="$4"
    local output
    output="$(ffmpeg -hide_banner -i "$source" -i "$encoded" -lavfi "$filter" -f null - 2>&1 || true)"
    printf '%s\n' "$output" | sed -n "$pattern" | tail -1
}

cd "$repo_dir"
bin/henyo launch com.android.settings --intent '表示形式の計測用画面を開きます' >/dev/null

for capture_index in 1 2 3; do
    if [[ "$capture_index" -gt 1 ]]; then
        bin/henyo scroll down --intent '表示形式の計測用画面を移動します' >/dev/null
    fi
    shot_json="$(bin/henyo screenshot --json --ttl 300 --prefix codec-benchmark \
        --intent '画像形式の計測用に画面を取得します')"
    source_path="$(python -c 'import json,sys; print(json.load(sys.stdin)["path"])' <<<"$shot_json")"
    if [[ "$source_path" != "$runtime_root"/henyo/screens/codec-benchmark-* || ! -f "$source_path" ]]; then
        echo "benchmark: unexpected screenshot artifact" >&2
        exit 1
    fi

    raw_path="$bench_dir/capture-${capture_index}.ppm"
    ffmpeg -hide_banner -loglevel error -y -i "$source_path" -frames:v 1 "$raw_path"
    dimensions="$(ffprobe -v error -select_streams v:0 \
        -show_entries stream=width,height -of csv=s=x:p=0 "$source_path")"
    printf 'capture=%s format=android-png-original dimensions=%s bytes=%s\n' \
        "$capture_index" "$dimensions" "$(wc -c < "$source_path" | tr -d ' ')"

    for format in png-reencode webp-lossless webp-q85 webp-q90 jpeg-q2 jpeg-q4; do
        case "$format" in
            png-reencode) suffix="png" ;;
            webp-*) suffix="webp" ;;
            jpeg-*) suffix="jpg" ;;
        esac
        output_path="$bench_dir/capture-${capture_index}-${format}.${suffix}"
        timings=""
        for _repeat in 1 2 3 4 5; do
            started="$(date +%s%N)"
            case "$format" in
                png-reencode)
                    ffmpeg -hide_banner -loglevel error -y -i "$raw_path" -frames:v 1 \
                        -compression_level 6 "$output_path"
                    ;;
                webp-lossless) cwebp -quiet -lossless "$raw_path" -o "$output_path" ;;
                webp-q85) cwebp -quiet -q 85 "$raw_path" -o "$output_path" ;;
                webp-q90) cwebp -quiet -q 90 "$raw_path" -o "$output_path" ;;
                jpeg-q2)
                    ffmpeg -hide_banner -loglevel error -y -i "$raw_path" -frames:v 1 \
                        -q:v 2 "$output_path"
                    ;;
                jpeg-q4)
                    ffmpeg -hide_banner -loglevel error -y -i "$raw_path" -frames:v 1 \
                        -q:v 4 "$output_path"
                    ;;
            esac
            ended="$(date +%s%N)"
            timings+="$(( (ended - started) / 1000 )),"
        done

        encoded_dimensions="$(ffprobe -v error -select_streams v:0 \
            -show_entries stream=width,height -of csv=s=x:p=0 "$output_path")"
        [[ "$dimensions" == "$encoded_dimensions" ]]
        psnr="$(metric '[0:v][1:v]psnr' 's/.*average:\([^ ]*\).*/\1/p' "$source_path" "$output_path")"
        ssim="$(metric '[0:v][1:v]ssim' 's/.*All:\([^ ]*\).*/\1/p' "$source_path" "$output_path")"
        edge_psnr="$(metric \
            '[0:v]edgedetect=mode=colormix:high=0.2[a];[1:v]edgedetect=mode=colormix:high=0.2[b];[a][b]psnr' \
            's/.*average:\([^ ]*\).*/\1/p' "$source_path" "$output_path")"
        printf 'capture=%s format=%s dimensions=%s bytes=%s medianEncodeMs=%s psnr=%s ssim=%s edgePsnr=%s\n' \
            "$capture_index" "$format" "$dimensions" \
            "$(wc -c < "$output_path" | tr -d ' ')" "$(median_ms "$timings")" \
            "${psnr:-n/a}" "${ssim:-n/a}" "${edge_psnr:-n/a}"
    done

    rm -f -- "$source_path"
    source_path=""
done
