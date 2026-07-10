# cgzyy-icu-syn-data-jar

ICU 数据处理任务：从 SmartCare MongoDB 读取**在院患者**（`patient.status = admitted`），
按 `patient.id = bedside.pid` 关联其 `bedside` 记录，再根据 `bedside.code` 分发到对应的业务处理逻辑。

## 技术栈

- Java 17
- Spring Boot 3.2.5
- Spring Data MongoDB
- Lombok

## 目录结构

```
src/main/java/com/digixmed/icu/viform/
├── SynDataApplication.java              # 启动类
├── entity/                             # 实体
│   ├── Patient.java                    # patient 集合（用户提供，仅调整包路径）
│   ├── PatBedHistorie.java             # 占位实体，字段待补全
│   ├── PatientOperations.java          # 占位实体，字段待补全
│   ├── Bedside.java                    # bedside 集合
│   └── BedsideHistory.java             # bedside.history 内嵌项
├── repository/                        # 仓库
│   ├── PatientRepository.java          # findByStatus("admitted")
│   └── BedsideRepository.java          # findByPid / findByPidIn / findByPidAndCode ...
├── service/                           # 业务
│   ├── AdmittedPatientBedsideService.java  # 主流程：拉取在院患者 + 关联 bedside + 分发
│   ├── BedsideCodeHandler.java         # 处理器接口（策略模式）
│   ├── BedsideCodeDispatcher.java      # 按 code 分发到对应 handler
│   └── handler/
│       └── HypothermiaTreatmentHandler.java  # 示例：param_亚低温治疗（逻辑待补全）
├── runner/
│   └── SynDataRunner.java              # 可选：启动时执行一次（syn.run-on-startup=true）
└── controller/
    └── SynDataController.java          # 手动触发 / 调试接口（可删除）
```

## 核心流程

1. `PatientRepository.findByStatus("admitted")` 查询在院患者。
2. 用患者 id 批量 `BedsideRepository.findByPidIn(...)`（可按 `syn.bedside-codes` 过滤 code）拉取 bedside，避免 N+1。
3. 按 `pid` 分组，逐条交给 `BedsideCodeDispatcher`，根据 `bedside.code` 找到对应 `BedsideCodeHandler` 执行业务逻辑。

## 扩展新的 code 处理逻辑

新增一个实现 `BedsideCodeHandler` 的 `@Component`，返回对应的 `supportedCode()` 即可自动注册，无需改动分发器：

```java
@Component
public class XxxHandler implements BedsideCodeHandler {
    @Override public String supportedCode() { return "param_xxx"; }
    @Override public void handle(Patient patient, Bedside bedside) {
        // 业务逻辑
    }
}
```

## 配置（application.yml）

| 配置项 | 说明 | 默认值 |
| --- | --- | --- |
| `spring.data.mongodb.*` | MongoDB 连接（支持环境变量覆盖） | host=10.35.4.11, port=32121, db=SmartCare |
| `server.port` | 服务端口 | 30001 |
| `syn.run-on-startup` | 是否启动时自动执行一次 | false |
| `syn.bedside-codes` | 关注的 code 列表（逗号分隔，空=全部） | param_亚低温治疗 |

## 运行

```bash
# 打包
mvn clean package

# 运行（可用环境变量覆盖配置）
java -jar target/cgzyy-icu-syn-data.jar

# 或启动时自动执行一次处理
java -jar target/cgzyy-icu-syn-data.jar --syn.run-on-startup=true
```

## 手动触发接口

```bash
# 健康检查
curl http://localhost:30001/syn/health

# 手动执行一次处理流程
curl -X POST http://localhost:30001/syn/process

# 查询某在院患者的 bedside 记录
curl http://localhost:30001/syn/patients/{patientId}/bedsides
```

## 说明 / 待办

- `PatBedHistorie`、`PatientOperations` 为占位实体，请按真实文档结构补全字段。
- `HypothermiaTreatmentHandler.handle` 目前仅打印日志，具体业务逻辑待补充。
- MongoDB 文档中的 `_class` 字段由 Spring Data 自动处理，无需在实体中声明。
