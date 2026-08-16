# 从源码构建

构建默认使用仓库内已提交的模型清单（`model-assets-manifest.json`），支持离线构建；仅当清单缺失或修订号不匹配时才回退到 Hugging Face 实时查询（需联网）。

**环境要求**

- JDK 25（Minecraft 26.x 需要）与 Gradle 9.5.1 wrapper
- `-windows` 构建需要 `libs/onnxruntime-dml.jar`，该文件随仓库提供（`libs/` 目录被 gitignore，此 jar 除外，切勿删除）

构建命令由两个正交参数控制：推理变体（`useDml` / `useCuda` / `useCpu`，互斥，**DML 默认**）与 MC 目标（`mcTarget`，默认 **261**）：

```
./gradlew build -PuseDml=true -PmcTarget=261   # Windows（DirectML）
./gradlew build -PuseCuda=true -PmcTarget=262  # NVIDIA
./gradlew build -PuseCpu=true -PmcTarget=263   # CPU + CoreML on macOS
./gradlew buildAllMc                           # 三个 MC 目标各构建一次（嵌套 gradlew，较慢）
```

- 有效 `mcTarget` 值：261（26.1.2）、262（26.2）、263（26.3-snapshot-3），定义于 `gradle.properties`；未知值会抛出 GradleException
- 产物版本形如 `{mod_version}-{windows|cuda|cpu}+{minecraft_version}`（当前 `3.0.1-beta-...`，见 `gradle.properties`）
- `./gradlew runClient` 启动开发客户端；`-PwithSourcesJar=true` 生成 sources jar；所有 JavaExec 任务强制使用仓库根目录的 `log4j2-dev.xml`
- `./gradlew pipelineTest` 运行 `PipelineTest.main`（JavaExec，`-Xmx8g`）：下载真实模型、生成一块 256 方块地形，若显存增量超过 2500 MB（经 `nvidia-smi` 测量）则失败。这是集成回归门禁，不是 JUnit 测试；单元测试（JUnit 5）用 `./gradlew test` 运行

**网络代理（部分开发机）**

出站流量走本地代理时，Gradle wrapper 下载器只读取 JVM 代理系统属性，不读环境变量：

```
$env:JAVA_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897"
```

首次构建还会下载 Gradle 9.5.1（约 150 MB），若默认 10 秒超时失败，可加 `-Dorg.gradle.wrapper.networkTimeout=60000`。

## 用 DirectML 构建 onnxruntime

**环境要求**

- [Windows 10 SDK (10.0.17134.0)](https://developer.microsoft.com/en-us/windows/downloads/sdk-archive/index-legacy) — 用于 Windows 10 1803 或更新版本
- Visual Studio 2017 工具链 — 在 VS Installer 中安装 *Desktop development with C++*
- Visual Studio 2022 工具链 — 同上
- Python 3.10+：[https://python.org/](https://python.org/)
- CMake 3.28 或更高版本

两个 VS 工具链都要保持最新。完整细节见 [ONNX Runtime 构建文档](https://onnxruntime.ai/docs/build/inferencing.html) 和 [DirectML EP 要求](https://onnxruntime.ai/docs/execution-providers/DirectML-ExecutionProvider.html#build)。

**步骤**

所有命令都在 **VS 2022 的 Developer Command Prompt** 中运行。

```
git clone --recursive https://github.com/Microsoft/onnxruntime.git
cd onnxruntime
.\build.bat --config RelWithDebInfo --build_shared_lib --parallel --compile_no_warning_as_error --skip_submodule_sync --use_dml --build_java --build
```

构建出的 jar 位于 `java/build/`。将其重命名为 `onnxruntime-dml.jar` 并放入本仓库的 `libs/` 目录。

# 给 Mod 开发者的说明

AI 地形的核心是三阶段扩散管线（coarse 20 步 DPM-Solver++ → latent 2 步 flow matching → decoder 1 步），模型输出高程 + 气候变量；与 Minecraft 的集成全靠手写规则。

- [BiomeClassifier.java](https://github.com/f1owkang/Terrain-Diffusion-Next/blob/mc26/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/BiomeClassifier.java)（约 360 行）——高程 + 4 气候变量 → 生物群系规则，含海岸带检测（海滩）与河道覆盖（河流/冻结河流）
- [RiverDetector.java](https://github.com/f1owkang/Terrain-Diffusion-Next/blob/mc26/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/RiverDetector.java)（D8 流向 + 汇流累积）与 [RiverCarver.java](https://github.com/f1owkang/Terrain-Diffusion-Next/blob/mc26/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/RiverCarver.java)（路径雕刻，移植自上游 PR #207）：hybrid 模式用 D8 在 halo 扩展窗口（64 原生像素）上算河网保证跨 tile 无缝，再沿路径雕刻出蓄水河道；`rivers.mode=carver` 可切回纯噪声雕刻
- [TerrainShaping.java](https://github.com/f1owkang/Terrain-Diffusion-Next/blob/mc26/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/TerrainShaping.java) — 山脊与高原塑形（`terrain.ridges.*` / `terrain.plateau.*` 配置），在模型输出的高程上叠加手写塑形
- [WonderGenerator.java](https://github.com/f1owkang/Terrain-Diffusion-Next/blob/mc26/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/WonderGenerator.java) — 稀有奇观（尖塔 / 火山口 / 桌山 / 石柱 / 峡谷），按生物群系门控、按世界种子确定性生成（`wonders.enabled` 配置）
- 河流、塑形与奇观参数全部在 `config/terrain-diffusion-next.properties` 的 `rivers.*` / `terrain.ridges.*` / `terrain.plateau.*` / `wonders.enabled` 配置，无需改代码

地形多样性远超生物群系多样性，弥合这一差距是实打实的机会。希望有人能把它做到极致。
