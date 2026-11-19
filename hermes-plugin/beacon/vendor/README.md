# Vendored HTTP/2 for agent_phone_grpc.py

Pure-Python h2c for the emulator's gRPC (EMU-DESIGN §0.5): Hermes ships no grpcio, protobuf or h2 (`pyproject.toml`).
Copied from the wheels the EMU-SPIKES venv installed from PyPI (S1, S3, S7 ran on them): h2 4.4.1, hpack 4.2.0,
hyperframe 6.1.0, all MIT (texts in `../licenses/`). Only the `.py` files are kept. The one change: h2's absolute
imports of `hpack` and `hyperframe` are made relative (`from ..hpack.hpack import …`), so the three load as one private
package (`agent_phone_grpc._vendor()`), never on `sys.path`, and never shadow another h2.

| File | sha256 of the upstream file | Change |
|---|---|---|
| `h2/__init__.py` | `1baaac023c2da4d25451ad290ea0909519ca9eeef92b9dc13cfc61f338252bae` | unchanged |
| `h2/_typing.py` | `a82c4f398c0b2e3d6feb190ccff82f1ab02815c499c4cbcd660a620648a292f2` | unchanged |
| `h2/config.py` | `67ec2be6b162cdc076f964d5582b86b5ef32fbc71e6f9076fab95c5742f9b6e5` | unchanged |
| `h2/connection.py` | `8283e14ad0875ecdd5d94fbd4c4111e3c08d298ef80ae6b3ff223c1ce1aa8e09` | imports made relative |
| `h2/errors.py` | `4be4dd61a25f3ff8709458f6b90189190e12e3fc96894ffd138e1a3f174009b1` | unchanged |
| `h2/events.py` | `ae36193d2ffac6f8cc9c06a4ed51db607c93aa5f206e314327952495c5df9bb1` | imports made relative |
| `h2/exceptions.py` | `dc49b798b310047c58a4e2a868f74a0861469fc03dadef4006d51d2a56376d93` | unchanged |
| `h2/frame_buffer.py` | `37febf771e38929065f7c270f555cdcceb386a1253e94dca481736bce08f6b47` | imports made relative |
| `h2/settings.py` | `f356a3a890f7ec16eca6c14f53cb2083433db9eaa73eb2bf424044a8ec0ba356` | imports made relative |
| `h2/stream.py` | `a52bed21bc71d78f7f004c44dec70450844d9c781b6e4d9f0236c3733065034a` | imports made relative |
| `h2/utilities.py` | `6250a082e47953214db7fba7ca3259f4afcdf538bb3a9800404bbf6975dcf13d` | imports made relative |
| `h2/windows.py` | `26958d843bfec782798c85d25a9d0834792af482203cfdbbc6bf455d8bdacc51` | unchanged |
| `hpack/__init__.py` | `8d7e285593c15d356e93f6b7c6856a51bb93e2cd6f95e7d74a091e5587b71471` | unchanged |
| `hpack/exceptions.py` | `5eacbcbe2aaf28a1c1c90ec8a3c169f873e50cfdd3ed133d6a994f4b176bf632` | unchanged |
| `hpack/hpack.py` | `4ab14aab1a856cee45aea3169854167b16dac9ef9ec4cef1ed1d5da82439769d` | unchanged |
| `hpack/huffman.py` | `cc3b91b4ec8511d29bf7f199acdb40a274c452ca882eb2b6b2d4a1e702e919ee` | unchanged |
| `hpack/huffman_constants.py` | `e0f84e31e97998dd1ab24151ae7e2756927f09339484ed6a6c544eeaefc87f63` | unchanged |
| `hpack/huffman_table.py` | `12452e981b596483c227a950ecb1bf6f07c38f58991225cce0035c5e34f5cd4b` | unchanged |
| `hpack/struct.py` | `06a10e4b74f996ae341257efec6ec24385f6a7209a93ce6657367f5c8cb1a083` | unchanged |
| `hpack/table.py` | `500937f5f52433fafc76704a3832ffa86afdff044e9ca4150761033f60032642` | unchanged |
| `hyperframe/__init__.py` | `43f3017642040964cbbe4b0e5e0e67607517f2303c861a10b2e8fa394436e252` | unchanged |
| `hyperframe/exceptions.py` | `0d92eb670e72ecc2c13119310b1aea7f9121cfc0681369fb32955aa780f9bdc2` | unchanged |
| `hyperframe/flags.py` | `f65be969e297adf61ebb18fc5deab6d440f8d0e0d38a9a376577835225f95992` | unchanged |
| `hyperframe/frame.py` | `6ac62192190eaf5bd10777ee6581351c88532050f424d3965b067cbde7735b15` | unchanged |
