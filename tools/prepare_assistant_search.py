"""Reproduce the pinned MiniFRIDA INT8 asset used by AWAssistant (Python 3.11+)."""
import argparse, hashlib, importlib.metadata, json, os, urllib.request
from pathlib import Path
import onnx
from onnxruntime.quantization import QuantType, quantize_dynamic

SOURCE='https://huggingface.co/crazyfrogspb/rubert-mini-frida-onnx/resolve/6af2c2b286c01d292ce0dadb3ade0fff585d7413/model.onnx'
SOURCE_SHA='38c72d258f4ee40ab87a6fba8103645ac93c5d82c560c534b07329ce3e362d4a'
OUTPUT_SHA='6839e61573be6adee64a252354a47b42a0aaabf6a78132885007f23291b92e62'

def digest(file):
    with file.open('rb') as stream:
        return hashlib.file_digest(stream,'sha256').hexdigest()

def main():
    p=argparse.ArgumentParser();p.add_argument('--output-dir',type=Path,required=True);args=p.parse_args()
    for package,version in [('onnx','1.20.1'),('onnxruntime','1.31.0')]:
        if importlib.metadata.version(package)!=version:raise RuntimeError('Use '+package+'=='+version+' to reproduce the published checksum')
    folder=args.output_dir;folder.mkdir(parents=True,exist_ok=True);source=folder/'frida.onnx';target=folder/'frida-int8.onnx';temporary=folder/'frida-int8.partial.onnx'
    if not source.exists() or digest(source)!=SOURCE_SHA:
        partial=folder/'frida.download'
        try:
            with urllib.request.urlopen(SOURCE,timeout=90) as response, partial.open('wb') as out:
                size=0
                while data:=response.read(65536):
                    size+=len(data)
                    if size>129223074:raise ValueError('Unexpected source size')
                    out.write(data)
            if digest(partial)!=SOURCE_SHA:raise ValueError('Source checksum mismatch')
            os.replace(partial,source)
        finally:partial.unlink(missing_ok=True)
    try:
        quantize_dynamic(str(source),str(temporary),weight_type=QuantType.QInt8,per_channel=True,op_types_to_quantize=['MatMul','Gather'])
        target_bytes=onnx.load(str(temporary)).SerializeToString(deterministic=True)
        if hashlib.sha256(target_bytes).hexdigest()!=OUTPUT_SHA:raise ValueError('Reproduced model checksum mismatch')
        target.write_bytes(target_bytes)
    finally:temporary.unlink(missing_ok=True)
    print(json.dumps({'file':str(target),'bytes':target.stat().st_size,'sha256':digest(target)}))

if __name__=='__main__':main()
