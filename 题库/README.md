# 题库格式说明

题库就是一个 UTF-8（无 BOM）的 JSON 文件，最大 1 MB，题目数量 5–500 道。

## 新格式（推荐，v2.9 起支持多学科分组）

```json
{
  "version": 3,
  "title": "总题库",
  "subjects": [
    {
      "key": "english",
      "name": "英语",
      "questions": [
        {
          "id": "en-001",
          "q": "选出每组单词中不同类的一项：",
          "options": ["bus", "bike", "pencil"],
          "answer": 2
        }
      ]
    },
    {
      "key": "math",
      "name": "数学",
      "questions": [...]
    }
  ]
}
```

## 旧格式（单学科）

```json
{
  "version": 2,
  "title": "英语",
  "questions": [
    { "q": "题干", "options": ["A 选项", "B 选项", "C 选项"], "answer": 0 }
  ]
}
```

也接受顶层直接是数组，此时整体视为一个学科，学科名取 `title`。

## 字段规则

| 字段 | 必填 | 规则 |
|---|---|---|
| `q` | 是 | 题干，非空，≤ 200 字 |
| `options` | 是 | 2–6 个选项，每项 ≤ 60 字 |
| `answer` | 是 | 正确选项下标，从 0 开始，必须在 `options` 范围内 |
| `id` | 否 | 唯一标识，用于统计/自动移出；不填时按题干生成 |
| `key` / `name` | 否 | 学科标识与显示名；缺省时归到默认学科 |

## 怎么导入

1. **局域网上传页（推荐）**：电视「家长设置」→ 输入 PIN → 「题库 / 局域网更新」页，页面会显示 `http://<电视IP>:8080/`，手机或电脑浏览器打开后粘贴 JSON 全文、填 PIN、点「上传并校验」。旧题库会自动备份为 `questions.bak`，页面可一键回退。
2. **adb push**：`adb push 你的题库.json /sdcard/Android/data/com.family.quizlauncher/files/questions_import.json`，下次打开 App 时自动校验并导入。
3. **U 盘**：把文件命名为 `questions_import.json` 放在 U 盘根目录，App 启动时自动拾取。

加载优先级：外部 `questions.json` → 家长自定义 → 内置算术题。

## 示例

[english_unit3.json](english_unit3.json) 是一份真实在用的六年级英语单选题库（15 题），可以直接拿来当格式参考。
