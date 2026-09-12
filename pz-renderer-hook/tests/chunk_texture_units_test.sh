#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd -P)"
source_file="$repo_root/pz-renderer-hook/src/main/java/zombie/core/ChunkBatchRenderer.java"

render_body="$(sed -n '/public void render()/,/private void uploadBatch/p' "$source_file")"

if grep -Fq 'chunk.color.bind();' <<<"$render_body"; then
    echo 'FAIL: cached Texture.bind() is unsafe after switching texture units'
    exit 1
fi

direct_binds="$(grep -Fc 'GL11.glBindTexture(GL_TEXTURE_2D' <<<"$render_body")"
if (( direct_binds < 2 )); then
    echo 'FAIL: every color and depth slot must be bound directly'
    exit 1
fi

echo 'chunk_texture_units_test: PASS'
