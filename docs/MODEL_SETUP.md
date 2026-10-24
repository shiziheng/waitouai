# 模型文件说明

## 用的是哪个模型

| 项目 | 值 |
|---|---|
| 模型 | MediaPipe Face Landmarker（float16） |
| 文件 | `app/src/main/assets/face_landmarker.task` |
| 大小 | 3,758,596 字节（约 3.6 MB） |
| SHA-256 | `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff` |
| 下载地址 | `https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task` |
| 官方文档 | https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/android |
| 许可 | 随 MediaPipe 发布，遵循 Apache License 2.0 |

## 怎么放进工程

模型必须放在 `app/src/main/assets/` 目录下，文件名固定为 `face_landmarker.task`。

构建配置里已经加了 `noCompress += "task"`——MediaPipe 是直接按文件描述符读模型文件的，如果被 AAPT 压缩过会读取失败。这一条别删。

## 模型丢了会怎样

App 不会崩，但会：

- 界面顶部弹出红色错误条，提示「识别模型加载失败」
- 状态一直是「识别没法用」
- 相机预览照常显示，只是不做判断

也就是说，不会伪造识别结果。

## 重新下载

如果文件损坏或者被误删：

1. 直接点上面那个下载地址，存成 `face_landmarker.task`
2. 放到 `app/src/main/assets/`
3. 校验一下大小是不是 3,758,596 字节

## 为什么选 Face Landmarker 而不是 ML Kit

ML Kit 的人脸检测要走 Google Play Services 下载模型，国内红米这类没有 GMS 的手机上首次使用会失败。Face Landmarker 的模型是随 APK 打包的，离线可用，不依赖 GMS。
