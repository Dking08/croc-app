#!/usr/bin/env python3
"""Keep vendored web assets as text for the F-Droid source scanner.

Run after go mod vendor in third_party/croc-src. Tailscale's web client
already falls back to uncompressed assets; its eventbus debugger needs
its embed pattern and handler adjusted to serve the same JavaScript.
"""

import base64
import gzip
import hashlib
import zlib
from pathlib import Path

vendor = Path(__file__).resolve().parents[1] / "third_party/croc-src/vendor"
# These streams are flushed, but not closed, by Tailscale's fetch-htmx.go.
# Verify the extracted text against that generator's SHA-384 hashes.
htmx_hashes = {
    "htmx.min.js.gz": "HGfztofotfshcF7+8n44JQL2oJmowVChPTg48S+jvZoztPfvwD79OC/LTtG6dMp+",
    "htmx-websocket.min.js.gz": "932iIqjARv+Gy0+r6RTGrfCkCKS5MsF539Iqf6Vt8L4YmbnnWI2DSFoMD90bvXd0",
}
for relative in (
    "tailscale.com/util/eventbus/assets",
    "github.com/tailscale/web-client-prebuilt/build/assets",
):
    for compressed in (vendor / relative).glob("*.gz"):
        target = compressed.with_suffix("")
        try:
            data = gzip.decompress(compressed.read_bytes())
        except EOFError:
            if compressed.name not in htmx_hashes:
                raise
            decoder = zlib.decompressobj(16 + zlib.MAX_WBITS)
            data = decoder.decompress(compressed.read_bytes()) + decoder.flush()
            digest = base64.b64encode(hashlib.sha384(data).digest()).decode()
            if digest != htmx_hashes[compressed.name]:
                raise ValueError(f"Unexpected contents in {compressed}")
        if target.exists() and target.read_bytes() != data:
            raise ValueError(f"Compressed asset differs from {target}")
        target.write_bytes(data)
        compressed.unlink()

source = vendor / "tailscale.com/util/eventbus/debughttp.go"
if source.exists():
    text = source.read_text()
    gzip_case = '\t\tcase strings.HasSuffix(name, ".min.js.gz"):\n\t\t\tw.Header().Set("Content-Type", "text/javascript")\n\t\t\tw.Header().Set("Content-Encoding", "gzip")\n'
    if ".min.js.gz" in text and gzip_case not in text:
        raise ValueError("Tailscale eventbus asset handler changed; review the patch")
    text = text.replace(gzip_case, "").replace(".min.js.gz", ".min.js")
    source.write_text(text)
