# 核心数据模型

## Repository
```json
{
  "id": "string",
  "url": "string",
  "commitSha": "string",
  "defaultBranch": "string",
  "language": "java",
  "framework": "spring",
  "fileCount": 0,
  "sizeBytes": 0,
  "analyzedAt": "ISO8601"
}
```

## CodeUnitInfo
```json
{
  "id": "string",
  "repositoryId": "string",
  "filePath": "src/main/java/.../OwnerController.java",
  "language": "java",
  "framework": "spring",
  "packageName": "org.springframework.samples.petclinic.owner",
  "name": "OwnerController",
  "kind": "class",
  "annotations": ["@Controller"],
  "fields": [
    { "name": "ownerRepository", "type": "OwnerRepository", "annotations": ["@Autowired"] }
  ],
  "startLine": 0,
  "endLine": 0
}
```

## MethodInfo
```json
{
  "id": "string",
  "codeUnitId": "string",
  "name": "processFindForm",
  "signature": "public String processFindForm(...)",
  "annotations": ["@GetMapping"],
  "startLine": 0,
  "endLine": 0
}
```

## DependencyEdge
```json
{
  "id": "string",
  "repositoryId": "string",
  "fromCodeUnitId": "string",
  "toCodeUnitId": "string",
  "kind": "import|field|annotation",
  "language": "java"
}
```

## QAReference
```json
{
  "file": "src/main/java/.../OwnerController.java",
  "language": "java",
  "startLine": 0,
  "endLine": 0,
  "content": "代码片段"
}
```

## AnalyzeResult
```json
{
  "repositoryId": "string",
  "language": "java",
  "framework": "spring",
  "codeUnits": [],
  "methods": [],
  "dependencies": [],
  "failedFiles": [
    { "filePath": "src/main/java/.../Broken.java", "reason": "ParseException: ..." }
  ]
}
```
> `failedFiles` 每项为 `{ filePath, reason }`，`reason` 为解析异常摘要。

## CodeUnitFileInfo
```json
{
  "relativePath": "src/main/java/.../OwnerController.java",
  "packageName": "org.springframework.samples.petclinic.owner",
  "unitName": "OwnerController",
  "language": "java"
}
```
说明：T2 扫描阶段输出，尚未解析注解与依赖；unitName 语言中立，Java 下为类名。

## RetrievedSnippet
```json
{
  "file": "src/main/java/.../OwnerController.java",
  "language": "java",
  "startLine": 0,
  "endLine": 0,
  "content": "代码片段",
  "score": 0.0
}
```
说明：T9 检索层输出；score 为检索相关性，可空。

## AnalysisTask
```json
{
  "taskId": "string",
  "repositoryId": "string",
  "status": "pending|running|done|failed",
  "language": "java",
  "progress": 0,
  "message": "string",
  "createdAt": "ISO8601",
  "updatedAt": "ISO8601"
}
```
说明：对应 T7 的 `POST /api/repos` 返回与 `GET /api/repos/{id}/status` 状态查询；status 取值 pending / running / done / failed。

## CloneResult
```json
{
  "localPath": "/tmp/codecompass/repo-xxx",
  "success": true,
  "errorMessage": null
}
```
说明：T1 浅克隆服务输出；success = false 时 errorMessage 必填。
