# HelloAI 登录页原创 AI 虚拟人物与机器人骨架生成提示词

## 目标

基于用户提供的正面人像照片，生成一套可用于 HelloAI 登录页的原创视觉资产：

1. 默认状态显示原创 AI 虚拟人物头像；
2. 鼠标移入时显示与人物严格对齐的机器人骨架结构层；
3. 人物和骨架必须来自同一角色设计基准，不能分别独立生成；
4. 视觉风格符合 HelloAI 当前紫色 `#7C3AED` 与青色 `#06B6D4` 的品牌体系；
5. 避免真人照片复刻、名人相似、品牌 Logo、示例站点配色和泛化赛博朋克视觉。

## 输入照片要求

使用用户提供的正面人像照片作为几何参考，不直接复制照片身份。

照片应满足：

- 正面视角；
- 双眼、鼻梁、下巴、颈部和肩线清晰；
- 头部和肩部完整；
- 无品牌 Logo；
- 无复杂背景；
- 分辨率建议不低于 800×800；
- 如果照片带有蓝色描边或黑色背景，生成前先去除描边并抠出人物。

## 总体制作流程

### 第一步：生成原创 AI 虚拟人物主图

将用户照片作为参考图上传，并使用下面的“人物主图提示词”。

生成目标不是照片增强，而是重新设计一个虚构数字人：

- 保留正面构图和主要几何关系；
- 不复制真实身份；
- 不生成写实证件照；
- 明显虚拟化；
- 服装简化为深色科技制服；
- 使用紫青边缘光。

### 第二步：选定人物基准图

从生成结果中选择一张作为唯一角色基准。

选择标准：

- 人物居中；
- 双眼水平；
- 头部和肩部完整；
- 轮廓清晰；
- 没有文字、Logo 和复杂背景；
- 表情平静、专业、可信赖；
- 紫青边缘光克制，不过度发光。

选定后，后续骨架必须基于这张图派生，不要重新生成另一张人物图。

### 第三步：生成机器人骨架覆盖层

将人物基准图作为输入，使用下面的“机器人骨架提示词”。

骨架不是医学 X 光，也不是人体骨骼，而是 AI 机械结构扫描层：

- 头部轮廓线；
- 面部关键点；
- 眼部结构；
- 鼻梁中心线；
- 下颌和颈部机械节点；
- 肩部结构线；
- 少量紫色和青色线路；
- 透明背景；
- 细线、低透明度、局部节点高亮。

### 第四步：检查人物与骨架对齐

将人物主图和骨架层放在同一个画布中检查。

必须重合的定位点：

- 左眼中心；
- 右眼中心；
- 鼻梁中心；
- 下巴尖端；
- 颈部中心；
- 左右肩线；
- 人物外轮廓；
- 画布顶部和底部裁切线。

如果骨架和人物错位，不要手动拉伸人物；应重新生成骨架，或在 SVG 中调整骨架路径。

### 第五步：导出资产

建议导出：

- `helloai-avatar-base.png`：人物主图，透明背景；
- `helloai-avatar-skeleton.svg`：机器人骨架层，透明背景，优先 SVG；
- `helloai-avatar-preview.png`：人物与骨架合成预览图。

人物和骨架必须使用相同画布尺寸和相同裁切比例。

## 人物主图提示词

### 中文提示词

```text
基于我上传的正面人像照片，创作一个完全原创的 HelloAI 登录页 AI 虚拟人物。只参考照片中的正面构图、头部比例、眼睛中心、鼻梁中心、下巴和肩线的大致几何关系，不要复制真实身份，不要生成真人照片复刻，不要像任何名人，不要保留证件照质感。

角色是一个专业、克制、可信赖的半写实数字人，年轻东亚男性外观但明显虚拟化，短黑发，表情平静友好，正面直视，头部到胸口完整可见。服装简化为深色、干净、现代的科技制服，不要复杂图案，不要品牌 Logo，不要文字。

使用 HelloAI 品牌视觉：深紫 #7C3AED 与青色 #06B6D4 的极细边缘光，少量紫青反射，高级、冷静、现代的产品界面风格。不要赛博朋克城市，不要霓虹背景，不要复杂装饰，不要荧光黄绿色，不要赛车元素。

人物居中，双肩完整，头部、肩部和胸口轮廓清晰，背景为透明背景或干净的纯色背景，适合后续抠图，并适合作为同画布机器人骨架 SVG 覆盖层的基准图。画面比例 4:5，保留头顶、双肩和胸口空间。
```

### 英文提示词

```text
Create an original HelloAI AI virtual person portrait using the uploaded front-facing portrait only as a geometric composition reference. Do not copy the person's identity, do not create a photorealistic replica, and do not resemble any celebrity. Preserve only the front-facing composition, approximate head proportions, eye centers, nose bridge center, chin position, neck and shoulder alignment.

The character is a professional, restrained and trustworthy semi-realistic digital human, with a young East Asian male appearance that is clearly fictional and virtualized. Short black hair, calm friendly expression, front-facing, head-to-chest half-body composition. Simplify the clothing into a clean dark modern technical jacket. No complex patterns, no brand logos, no text.

Use the HelloAI brand palette: subtle purple #7C3AED and cyan #06B6D4 rim lighting, with restrained purple-cyan reflections. Premium, calm, modern product UI aesthetic. No cyberpunk city, no neon background, no complex decoration, no lime green, no racing elements.

Center the character, keep both shoulders fully visible, keep the head, shoulders and chest silhouette clear, use a transparent or clean solid background, and make the result suitable as the base layer for a perfectly aligned robot skeleton SVG overlay. Aspect ratio 4:5, with enough space above the head, around the shoulders and below the chest.
```

## 机器人骨架提示词

### 当前素材评估

`doc/helloai_avatar` 目录中的当前版本可以作为第一版方向验证，但不建议直接作为最终登录页资产：

- 人物主图整体可用，构图、发型、服装和紫青边缘光符合 HelloAI 方向；
- 骨架层与人物主图基本对齐，但视觉过于单薄；
- 当前骨架更像蓝色线框草图或技术蓝图，缺少机械体积、材质层次和结构厚度；
- 眼睛和面部结构有辨识度，但颈部、胸口和肩部的机械关系不够明确；
- 骨架 PNG 当前不是透明背景，直接用于登录页会出现黑色底；
- 需要重新生成“有体积、有材质、有机械层次”的骨架覆盖层，而不是继续加更多细线。

### 优化后的中文提示词

```text
基于我上传的原创 AI 虚拟人物主图，生成一个与人物严格对齐的机器人骨架结构覆盖层。不要改变人物姿态，不要重新设计人物，不要移动头部、眼睛、鼻梁、下巴、颈部或肩线的位置。骨架必须与人物使用完全相同的画布尺寸、视角、比例和裁切边界。

骨架不是医学 X 光，不是人体骨骼，也不是简单蓝色线框草图。它应该像人物皮肤下方真实存在的 AI 机械结构，在鼠标移入时从人物内部显现出来。请生成具有体积感、材质感和明确机械层次的机器人骨架：头部外轮廓、面部结构分片、左右眼机械模块、鼻梁中心结构、下颌机械骨架、颈部多层连接结构、肩部机械关节、胸口上方的核心结构和少量内部连接线。

骨架需要有清晰的层级：第一层是较粗的机械主结构线，第二层是较细的辅助结构线，第三层是少量机械节点和扫描高光。主结构线不能完全细如发丝，应具有可见的厚度和稳定性；辅助结构线可以更细，但不能变成杂乱网格。颈部和胸口区域要体现真实的机械连接关系，肩部关节要有圆形或环形机械结构，眼部要有明确的 AI 视觉模块感。

视觉风格必须符合 HelloAI 品牌：主要使用紫色 #7C3AED 和青色 #06B6D4，少量白色高光。骨架整体透明背景，主结构可以使用低透明度深紫填充或半透明材质，辅助线使用紫青渐变，局部节点轻微发光。整体要专业、克制、可信赖，不要恐怖，不要血腥，不要医学感，不要赛博朋克城市，不要复杂电路背景，不要文字，不要 Logo。

骨架应该看起来像从人物表面下显现出的 AI 机械生命结构，而不是覆盖在人物脸上的平面线稿。优先输出透明背景 SVG；如果只能输出图片，请输出透明背景 PNG，并确保与人物主图尺寸完全一致。
```

### 优化后的英文提示词

```text
Create a robot skeleton structural overlay based on the uploaded original AI virtual person portrait. The skeleton must be perfectly aligned with the person. Do not change the pose, do not redesign the person, and do not move the head, eyes, nose bridge, chin, neck or shoulder positions. Use the exact same canvas size, viewpoint, scale and crop boundaries as the base portrait.

This is not a medical X-ray, not a human skeleton, and not a simple blue wireframe sketch. It should feel like a real AI mechanical structure existing beneath the person's skin, revealed when the user hovers over the portrait. Create a robot skeleton with volume, material depth and clear mechanical hierarchy: head outline, facial structural plates, left and right robotic eye modules, nose bridge center structure, jaw mechanics, multi-layer neck connectors, shoulder mechanical joints, upper-chest core structure and a small number of internal connection lines.

Use a clear visual hierarchy: the first layer consists of thicker primary mechanical structure lines, the second layer consists of thinner auxiliary structure lines, and the third layer consists of a small number of mechanical nodes and scan highlights. Primary structure lines must not be as thin as hair; they should have visible thickness and stability. Auxiliary lines may be thinner, but must not become a chaotic mesh. The neck and chest should show credible mechanical connections. Shoulder joints should include circular or ring-shaped mechanical structures. The eyes should feel like deliberate AI vision modules.

Use the HelloAI brand palette: primarily purple #7C3AED and cyan #06B6D4, with a small amount of white highlights. Use a transparent background. Primary structures may use low-opacity deep purple fills or translucent material, auxiliary lines may use a purple-cyan gradient, and a few nodes may glow slightly. The result should feel professional, restrained and trustworthy. No horror, no gore, no medical feeling, no cyberpunk city, no complex circuit background, no text, no logos.

The skeleton should feel like an AI mechanical life structure emerging from beneath the person's surface, not a flat line drawing pasted over the face. Prefer a transparent SVG. If SVG is not available, export a transparent PNG with the exact same dimensions as the base portrait.
```

### 骨架负面提示词

```text
不要简单蓝色线框草图，不要发丝级细线，不要平面蓝图，不要杂乱网格，不要医学 X 光，不要真实人体骨骼，不要血腥效果，不要恐怖效果，不要黑色背景，不要白色背景，不要文字，不要 Logo，不要水印，不要赛博朋克城市，不要复杂电路背景，不要大面积发光，不要改变人物姿态，不要改变头部位置，不要改变眼睛中心，不要改变下巴和肩线，不要让骨架超出人物轮廓。
```

英文负面提示词：

```text
No simple blue wireframe sketch, no hair-thin lines, no flat blueprint, no chaotic mesh, no medical X-ray, no realistic human bones, no gore, no horror effect, no black background, no white background, no text, no logos, no watermark, no cyberpunk city, no complex circuit background, no large glowing areas, do not change the pose, do not move the head, do not move the eye centers, do not change the chin or shoulder lines, do not let the skeleton extend beyond the person's silhouette.
```

## 负面提示词

生成人物和骨架时都可以附加以下负面提示词：

```text
不要真人照片复刻，不要名人相似，不要证件照，不要身份证边框，不要姓名或证件号码，不要品牌 Logo，不要文字，不要水印，不要赛博朋克城市，不要复杂霓虹背景，不要荧光黄绿色，不要赛车元素，不要夸张机器人卡通脸，不要医学 X 光，不要真实人体骨骼，不要血腥效果，不要恐怖效果，不要杂乱网格，不要大面积发光，不要改变人物姿态，不要改变头部位置，不要改变眼睛中心，不要改变下巴和肩线。
```

英文负面提示词：

```text
No photorealistic identity replica, no celebrity likeness, no ID photo style, no ID card border, no name or ID number, no brand logos, no text, no watermark, no cyberpunk city, no complex neon background, no lime green, no racing elements, no exaggerated cartoon robot face, no medical X-ray, no realistic human bones, no gore, no horror effect, no chaotic mesh, no large glowing areas, do not change the pose, do not move the head, do not move the eye centers, do not change the chin or shoulder lines.
```

## 对齐校验提示词

如果使用的 AI 工具支持图像检查，可以使用下面的提示词：

```text
请检查人物主图和机器人骨架层是否严格对齐。重点检查左眼中心、右眼中心、鼻梁中心、下巴尖端、颈部中心、左右肩线、人物外轮廓和画布裁切边界。请指出任何错位、拉伸、旋转、比例不一致或骨架超出人物轮廓的问题，并给出需要调整的具体位置。
```

## 登录页实现建议

最终页面不建议直接使用 GIF 或视频。推荐使用两层素材：

```text
人物主图
  └── 机器人骨架 SVG 覆盖层
```

交互方式：

- 默认显示人物主图；
- 鼠标移入时，骨架层通过 `clip-path` 椭圆从顶部向下揭示；
- 鼠标移出时反向恢复；
- 键盘聚焦时也应显示骨架；
- 触摸设备点击时切换显示状态；
- `prefers-reduced-motion` 下使用简单淡入淡出。

## 验收标准

- 人物是原创数字人，不是真人照片复刻；
- 人物不像任何名人或已有角色；
- 没有品牌 Logo、文字、水印和证件信息；
- 人物与骨架画布尺寸一致；
- 双眼、鼻梁、下巴、颈部和肩线完全对齐；
- 骨架是 AI 机械结构扫描层，不是医学 X 光；
- 紫色和青色使用克制，符合 HelloAI 当前品牌；
- 骨架优先使用透明 SVG；
- 默认人物状态清晰，悬停骨架状态可辨识；
- 键盘、触摸和减少动画模式都有可用状态。
