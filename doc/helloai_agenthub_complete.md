# HelloAI AgentHub — 打卡上班调度系统完整方案

> 归档说明
>
> - 本文件保留为早期完整草案，记录了 AgentHub 方向的原始设想与灵感来源。
> - 其中关于 `AgentStatus` 扩展、WebSocket 主通道、ShiftManager 班次管理等实现方式，与 HelloAI 当前项目基线并不完全一致，后续不再直接作为开发主参考。
> - 当前用于持续扩展的主文档已收口到 `doc/HelloAI_agenthub.md`。
> - 若本文件与当前代码、基线文档或 `doc/HelloAI_agenthub.md` 存在冲突，优先以后者为准。
>
> 像管理外卖骑手一样管理 AI Agent：打卡上班、实时派单、完成下班。

---

## 一、需求确定

### 1.1 核心痛点
第三方AI Agent（Claude Code、Trae、Qoder、Codex等）接入调度平台后，无法"第一时间"获知分配的任务。现有双心跳检测+任务编排兜底方案，仍无法解决实时性问题。

### 1.2 解决思路
**"打卡上班"模式**：Agent主动签到上岗，平台按"在岗"状态派单，任务完成后"下班"断连。
- 长连接 = 上班时段的"在线状态"
- 心跳 = 考勤打卡
- 派单只派给"在岗"Agent
- 任务完成可选"继续接单"或"下班"

### 1.3 关键设计原则
- **通知即时，消费自主**：WebSocket只发通知（taskId+role），Agent收到后主动拉取（pullTasks→claim→执行）
- **通知丢失不致命**：Agent有兜底拉取机制
- **业务语义替代技术术语**："打卡/下班"比"连接/断开"更易理解

---

## 二、Agent状态枚举（扩展后）

```java
public enum AgentStatus {
    OFFLINE,      // 离线：未连接，不可派单
    ON_DUTY,      // 在岗：长连接正常，空闲可接单（新增）
    WORKING,      // 执行中：已分配任务，处理中
    OFF_DUTY,     // 下班：主动断开，可重新打卡（新增）
    SUSPENDED,    // 暂停：平台暂停其接单（违规/质量差）（新增）
    SLEEPING      // 休眠：保留，兼容现有逻辑
}
```

### 状态流转图
```
OFFLINE(离线)
  → checkIn() ──────────────────────────────→ ON_DUTY(在岗空闲)
                                                  │
                                                  │ 分配任务
                                                  ↓
                                              WORKING(执行中)
                                                  │
                          ┌───────────────────────┼───────────────────────┐
                          │                       │                       │
                          ↓                       ↓                       ↓
                    taskDone                    taskDone              心跳超时/
                    (stay=true)                 (stay=false)          平台强制
                          │                       │                       │
                          ↓                       ↓                       ↓
                      ON_DUTY                 OFF_DUTY               OFFLINE
                                                  │                       ↑
                                                  │ checkIn()             │
                                                  └───────────────────────┘
```

---

## 三、MCP工具设计（Agent侧调用）

### 3.1 checkIn — 打卡上班

```java
@McpTool(name = "checkIn", description = "打卡上班，建立长连接，开始接单")
public CheckInResult checkIn(
    @McpParam(description = "Agent ID") String agentId,
    @McpParam(description = "角色：EXECUTOR/PLANNER/REVIEWER/PATROL") String role,
    @McpParam(description = "技能清单") List<String> skills,
    @McpParam(description = "最大并发数") int maxConcurrent,
    @McpParam(description = "工作模式：FULL_TIME(全职)/PART_TIME(兼职)/SPOT(临时工)") String workMode
) {
    // 1. 验证Agent身份
    Agent agent = agentService.validate(agentId);

    // 2. 关闭旧班次（如果存在）
    String oldShift = agent.getCurrentShiftId();
    if (oldShift != null) {
        shiftManager.forceCheckOut(agentId, oldShift, "REPLACE");
    }

    // 3. 生成班次信息
    String shiftId = generateShiftId();
    String wsToken = generateWsToken(agentId, shiftId);
    Instant expiresAt = Instant.now().plus(8, ChronoUnit.HOURS);

    // 4. 更新Agent状态
    agent.setStatus(AgentStatus.ON_DUTY);
    agent.setCurrentShiftId(shiftId);
    agent.setSkills(skills);
    agent.setMaxConcurrent(maxConcurrent);
    agent.setWorkMode(WorkMode.valueOf(workMode));
    agent.setOnDutyAt(Instant.now());
    agentService.save(agent);

    // 5. 保存班次记录
    ShiftRecord shift = ShiftRecord.builder()
        .shiftId(shiftId)
        .agentId(agentId)
        .role(role)
        .skills(skills)
        .maxConcurrent(maxConcurrent)
        .workMode(workMode)
        .status(ShiftStatus.ACTIVE)
        .startedAt(Instant.now())
        .expiresAt(expiresAt)
        .lastHeartbeat(Instant.now())
        .build();
    shiftService.save(shift);

    // 6. 返回连接信息
    return CheckInResult.builder()
        .shiftId(shiftId)
        .wsUrl("wss://your-platform.com/ws/agent?token=" + wsToken)
        .token(wsToken)
        .expiresAt(expiresAt)
        .heartbeatInterval(30) // 30秒心跳
        .message("打卡成功，开始接单")
        .build();
}
```

**CheckInResult结构**
```java
@Data
@Builder
public class CheckInResult {
    private String shiftId;           // 班次ID
    private String wsUrl;             // WebSocket连接地址
    private String token;             // 临时Token
    private Instant expiresAt;        // 班次过期时间
    private int heartbeatInterval;    // 建议心跳间隔（秒）
    private String message;           // 提示信息
}
```

### 3.2 checkOut — 下班

```java
@McpTool(name = "checkOut", description = "下班，断开长连接，停止接单")
public void checkOut(
    @McpParam(description = "Agent ID") String agentId,
    @McpParam(description = "班次ID") String shiftId,
    @McpParam(description = "下班原因：DONE(完成)/BREAK(休息)/TIMEOUT(超时)/FORCE(强制)") String reason
) {
    // 1. 验证班次
    ShiftRecord shift = shiftService.getById(shiftId);
    if (!shift.getAgentId().equals(agentId)) {
        throw new SecurityException("班次归属不匹配");
    }

    // 2. 关闭WebSocket连接
    shiftManager.closeShift(shiftId);

    // 3. 交回未完成任务
    List<SubTask> unfinished = subTaskService.findByAgentAndStatus(agentId, 
        Arrays.asList(SubTaskStatus.ASSIGNED, SubTaskStatus.IN_PROGRESS));
    unfinished.forEach(task -> {
        task.setStatus(SubTaskStatus.PENDING);
        task.setAssignedAgentId(null);
        subTaskService.save(task);
        // 触发重新调度
        subTaskDispatchService.dispatch(task);
    });

    // 4. 更新班次记录
    shift.setStatus(ShiftStatus.CLOSED);
    shift.setEndedAt(Instant.now());
    shift.setCloseReason(reason);
    shiftService.save(shift);

    // 5. 更新Agent状态
    Agent agent = agentService.getById(agentId);
    agent.setStatus(AgentStatus.OFF_DUTY);
    agent.setCurrentShiftId(null);
    agent.setOffDutyAt(Instant.now());
    agentService.save(agent);

    log.info("Agent下班: agentId={}, shiftId={}, reason={}, unfinishedTasks={}", 
        agentId, shiftId, reason, unfinished.size());
}
```

### 3.3 taskDone — 任务完成

```java
@McpTool(name = "taskDone", description = "任务完成，选择继续接单或下班")
public TaskDoneResult taskDone(
    @McpParam(description = "Agent ID") String agentId,
    @McpParam(description = "任务ID") String taskId,
    @McpParam(description = "是否继续接单") boolean stayOnDuty,
    @McpParam(description = "执行结果") String result,
    @McpParam(description = "执行状态：SUCCESS/FAILED/BLOCKED") String status
) {
    // 1. 回写任务结果
    SubTask task = subTaskService.getById(taskId);
    task.setResult(result);
    task.setStatus(SubTaskStatus.valueOf(status));
    task.setCompletedAt(Instant.now());
    subTaskService.save(task);

    // 2. 触发后续流程（REVIEW/DONE等）
    subTaskStateMachine.fire(task, SubTaskStatus.valueOf(status));

    // 3. 释放Agent执行状态
    Agent agent = agentService.getById(agentId);
    agent.setCurrentTaskCount(agent.getCurrentTaskCount() - 1);

    if (stayOnDuty) {
        // 继续接单
        agent.setStatus(AgentStatus.ON_DUTY);
        agentService.save(agent);

        return TaskDoneResult.builder()
            .status("ON_DUTY")
            .message("任务完成，继续接单")
            .nextTaskCheck(true)
            .build();
    } else {
        // 下班
        String shiftId = agent.getCurrentShiftId();
        checkOut(agentId, shiftId, "DONE");

        return TaskDoneResult.builder()
            .status("OFF_DUTY")
            .message("任务完成，已下班")
            .nextTaskCheck(false)
            .build();
    }
}
```

---

## 四、Java WebSocket Server + 班次管理（ShiftManager）

### 4.1 WebSocket配置

```java
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private AgentWebSocketHandler agentWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentWebSocketHandler, "/ws/agent")
            .setAllowedOrigins("*")
            .addInterceptors(new AgentWebSocketInterceptor());
    }
}
```

### 4.2 WebSocket拦截器（鉴权）

```java
@Component
public class AgentWebSocketInterceptor implements HandshakeInterceptor {

    @Autowired
    private JwtTokenUtil jwtTokenUtil;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {

        // 从URL参数获取token
        String query = request.getURI().getQuery();
        String token = extractParam(query, "token");

        if (token == null || !jwtTokenUtil.validate(token)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        // 解析token获取agentId和shiftId
        Claims claims = jwtTokenUtil.parse(token);
        attributes.put("agentId", claims.get("agentId"));
        attributes.put("shiftId", claims.get("shiftId"));
        attributes.put("role", claims.get("role"));

        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
    }
}
```

### 4.3 WebSocket处理器

```java
@Component
@Slf4j
public class AgentWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private ShiftManager shiftManager;

    @Autowired
    private AgentService agentService;

    @Autowired
    private ObjectMapper objectMapper;

    // shiftId -> WebSocketSession
    private final ConcurrentHashMap<String, WebSocketSession> shiftSessions = new ConcurrentHashMap<>();

    // agentId -> shiftId
    private final ConcurrentHashMap<String, String> agentShifts = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Map<String, Object> attrs = session.getAttributes();
        String agentId = (String) attrs.get("agentId");
        String shiftId = (String) attrs.get("shiftId");
        String role = (String) attrs.get("role");

        // 保存连接
        shiftSessions.put(shiftId, session);
        agentShifts.put(agentId, shiftId);

        // 通知ShiftManager
        shiftManager.onConnected(shiftId, agentId, session);

        // 发送欢迎消息
        sendMessage(session, Map.of(
            "type", "SHIFT_START",
            "shiftId", shiftId,
            "agentId", agentId,
            "role", role,
            "message", "连接成功，开始接单",
            "timestamp", System.currentTimeMillis()
        ));

        log.info("Agent连接: agentId={}, shiftId={}, role={}", agentId, shiftId, role);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        AgentWsMessage wsMsg = objectMapper.readValue(payload, AgentWsMessage.class);

        String shiftId = (String) session.getAttributes().get("shiftId");
        String agentId = (String) session.getAttributes().get("agentId");

        switch (wsMsg.getType()) {
            case "HEARTBEAT":
                handleHeartbeat(shiftId, agentId, wsMsg);
                break;
            case "TASK_RESULT":
                handleTaskResult(shiftId, agentId, wsMsg);
                break;
            case "TASK_CLAIM":
                handleTaskClaim(shiftId, agentId, wsMsg);
                break;
            case "BLOCKED":
                handleBlocked(shiftId, agentId, wsMsg);
                break;
            case "STATUS_UPDATE":
                handleStatusUpdate(shiftId, agentId, wsMsg);
                break;
            default:
                log.warn("未知消息类型: {}", wsMsg.getType());
        }
    }

    private void handleHeartbeat(String shiftId, String agentId, AgentWsMessage msg) {
        shiftManager.onHeartbeat(shiftId, agentId);

        // 回复心跳确认
        sendMessage(shiftSessions.get(shiftId), Map.of(
            "type", "HEARTBEAT_ACK",
            "shiftId", shiftId,
            "timestamp", System.currentTimeMillis()
        ));
    }

    private void handleTaskResult(String shiftId, String agentId, AgentWsMessage msg) {
        // 处理任务结果上报
        String taskId = (String) msg.getPayload().get("taskId");
        String result = (String) msg.getPayload().get("result");
        String status = (String) msg.getPayload().get("status");

        // 复用taskDone逻辑
        // ...
    }

    private void handleTaskClaim(String shiftId, String agentId, AgentWsMessage msg) {
        // 处理任务抢占确认
        String taskId = (String) msg.getPayload().get("taskId");
        boolean claimed = (Boolean) msg.getPayload().get("claimed");

        if (claimed) {
            shiftManager.onTaskClaimed(shiftId, agentId, taskId);
        }
    }

    private void handleBlocked(String shiftId, String agentId, AgentWsMessage msg) {
        // 处理任务阻塞上报
        String taskId = (String) msg.getPayload().get("taskId");
        String reason = (String) msg.getPayload().get("reason");

        shiftManager.onTaskBlocked(shiftId, agentId, taskId, reason);
    }

    private void handleStatusUpdate(String shiftId, String agentId, AgentWsMessage msg) {
        // 处理状态更新（如进度上报）
        String taskId = (String) msg.getPayload().get("taskId");
        int progress = (Integer) msg.getPayload().get("progress");

        // 更新任务进度，可用于看板展示
        // ...
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String shiftId = (String) session.getAttributes().get("shiftId");
        String agentId = (String) session.getAttributes().get("agentId");

        shiftSessions.remove(shiftId);
        agentShifts.remove(agentId);

        // 非主动checkOut导致的断开，标记异常
        if (status.getCode() != 1000) { // 1000 = 正常关闭
            shiftManager.onAbnormalDisconnect(shiftId, agentId, status.getReason());
        }

        log.info("Agent断开: agentId={}, shiftId={}, status={}", agentId, shiftId, status);
    }

    // 发送消息给指定班次
    public void sendToShift(String shiftId, Map<String, Object> message) {
        WebSocketSession session = shiftSessions.get(shiftId);
        if (session != null && session.isOpen()) {
            sendMessage(session, message);
        }
    }

    // 发送消息给指定Agent（当前班次）
    public void sendToAgent(String agentId, Map<String, Object> message) {
        String shiftId = agentShifts.get(agentId);
        if (shiftId != null) {
            sendToShift(shiftId, message);
        }
    }

    // 广播给所有在线Agent（按角色筛选）
    public void broadcastToRole(String role, Map<String, Object> message) {
        shiftSessions.forEach((shiftId, session) -> {
            String agentRole = (String) session.getAttributes().get("role");
            if (role.equals(agentRole) && session.isOpen()) {
                sendMessage(session, message);
            }
        });
    }

    private void sendMessage(WebSocketSession session, Map<String, Object> message) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        } catch (IOException e) {
            log.error("发送消息失败: {}", e.getMessage());
        }
    }
}
```

### 4.4 ShiftManager（班次管理核心）

```java
@Component
@Slf4j
public class ShiftManager {

    @Autowired
    private AgentWebSocketHandler webSocketHandler;

    @Autowired
    private AgentService agentService;

    @Autowired
    private ShiftService shiftService;

    @Autowired
    private SubTaskService subTaskService;

    @Autowired
    private SubTaskDispatchService subTaskDispatchService;

    @Autowired
    private ResilientDispatcher resilientDispatcher;

    // 班次ID -> 连接信息
    private final ConcurrentHashMap<String, ShiftConnection> connections = new ConcurrentHashMap<>();

    // Agent连接建立
    public void onConnected(String shiftId, String agentId, WebSocketSession session) {
        connections.put(shiftId, ShiftConnection.builder()
            .shiftId(shiftId)
            .agentId(agentId)
            .session(session)
            .connectedAt(Instant.now())
            .lastHeartbeat(Instant.now())
            .build());

        // 更新班次心跳
        shiftService.updateHeartbeat(shiftId);
    }

    // 心跳处理
    public void onHeartbeat(String shiftId, String agentId) {
        ShiftConnection conn = connections.get(shiftId);
        if (conn != null) {
            conn.setLastHeartbeat(Instant.now());
        }
        shiftService.updateHeartbeat(shiftId);

        log.debug("心跳: agentId={}, shiftId={}", agentId, shiftId);
    }

    // 任务抢占确认
    public void onTaskClaimed(String shiftId, String agentId, String taskId) {
        // 标记Agent为WORKING
        Agent agent = agentService.getById(agentId);
        agent.setStatus(AgentStatus.WORKING);
        agent.setCurrentTaskCount(agent.getCurrentTaskCount() + 1);
        agentService.save(agent);

        log.info("任务抢占: agentId={}, taskId={}", agentId, taskId);
    }

    // 任务阻塞
    public void onTaskBlocked(String shiftId, String agentId, String taskId, String reason) {
        // 标记任务BLOCKED
        SubTask task = subTaskService.getById(taskId);
        task.setStatus(SubTaskStatus.BLOCKED);
        task.setBlockReason(reason);
        subTaskService.save(task);

        // 释放Agent
        Agent agent = agentService.getById(agentId);
        agent.setCurrentTaskCount(agent.getCurrentTaskCount() - 1);
        if (agent.getCurrentTaskCount() <= 0) {
            agent.setStatus(AgentStatus.ON_DUTY);
        }
        agentService.save(agent);

        log.warn("任务阻塞: agentId={}, taskId={}, reason={}", agentId, taskId, reason);
    }

    // 异常断开处理
    public void onAbnormalDisconnect(String shiftId, String agentId, String reason) {
        log.warn("异常断开: agentId={}, shiftId={}, reason={}", agentId, shiftId, reason);

        // 强制下班
        forceCheckOut(agentId, shiftId, "ABNORMAL_DISCONNECT: " + reason);
    }

    // 强制下班
    public void forceCheckOut(String agentId, String shiftId, String reason) {
        ShiftConnection conn = connections.remove(shiftId);

        // 关闭WebSocket
        if (conn != null && conn.getSession().isOpen()) {
            try {
                conn.getSession().close(CloseStatus.SERVICE_RESTARTED);
            } catch (IOException e) {
                log.error("关闭连接失败: {}", e.getMessage());
            }
        }

        // 交回未完成任务
        List<SubTask> unfinished = subTaskService.findByAgentAndStatus(agentId,
            Arrays.asList(SubTaskStatus.ASSIGNED, SubTaskStatus.IN_PROGRESS));

        unfinished.forEach(task -> {
            task.setStatus(SubTaskStatus.PENDING);
            task.setAssignedAgentId(null);
            subTaskService.save(task);

            // 重新调度
            subTaskDispatchService.dispatch(task);
        });

        // 更新班次
        ShiftRecord shift = shiftService.getById(shiftId);
        shift.setStatus(ShiftStatus.FORCE_CLOSED);
        shift.setEndedAt(Instant.now());
        shift.setCloseReason(reason);
        shiftService.save(shift);

        // 更新Agent
        Agent agent = agentService.getById(agentId);
        agent.setStatus(AgentStatus.OFFLINE);
        agent.setCurrentShiftId(null);
        agent.setCurrentTaskCount(0);
        agentService.save(agent);

        log.info("强制下班: agentId={}, shiftId={}, unfinishedTasks={}, reason={}",
            agentId, shiftId, unfinished.size(), reason);
    }

    // 关闭班次（正常下班）
    public void closeShift(String shiftId) {
        ShiftConnection conn = connections.remove(shiftId);
        if (conn != null && conn.getSession().isOpen()) {
            try {
                conn.getSession().close(CloseStatus.NORMAL);
            } catch (IOException e) {
                log.error("关闭连接失败: {}", e.getMessage());
            }
        }
    }

    // 通知Agent新任务（只发通知，不推数据）
    public void notifyNewTask(String agentId, String taskId, String role, int priority) {
        String shiftId = agentService.getById(agentId).getCurrentShiftId();
        if (shiftId == null) return;

        ShiftConnection conn = connections.get(shiftId);
        if (conn == null || !conn.getSession().isOpen()) return;

        webSocketHandler.sendToShift(shiftId, Map.of(
            "type", "NEW_TASK",
            "taskId", taskId,
            "role", role,
            "priority", priority,
            "timestamp", System.currentTimeMillis()
        ));

        log.info("任务通知: agentId={}, taskId={}", agentId, taskId);
    }

    // 获取在线Agent（按角色）
    public List<Agent> getOnlineAgentsByRole(String role) {
        return connections.values().stream()
            .filter(conn -> {
                Agent agent = agentService.getById(conn.getAgentId());
                return agent.getRole().equals(role) 
                    && agent.getStatus() == AgentStatus.ON_DUTY;
            })
            .map(conn -> agentService.getById(conn.getAgentId()))
            .collect(Collectors.toList());
    }

    @Data
    @Builder
    public static class ShiftConnection {
        private String shiftId;
        private String agentId;
        private WebSocketSession session;
        private Instant connectedAt;
        private Instant lastHeartbeat;
    }
}
```

### 4.5 任务分配时触发通知

```java
@Service
@Slf4j
public class SubTaskDispatchService {

    @Autowired
    private ShiftManager shiftManager;

    @Autowired
    private AgentSelector agentSelector;

    @Autowired
    private SubTaskService subTaskService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    public void dispatch(SubTask subTask) {
        String role = subTask.getRequiredRole();

        // 1. 筛选ON_DUTY状态的Agent
        List<Agent> candidates = shiftManager.getOnlineAgentsByRole(role);

        if (candidates.isEmpty()) {
            // 无在岗Agent，进入等待队列
            log.warn("无在岗Agent: role={}, taskId={}", role, subTask.getId());
            subTask.setStatus(SubTaskStatus.WAITING_ON_DUTY);
            subTaskService.save(subTask);
            return;
        }

        // 2. 按规则选择Agent（负载+评分+技能匹配）
        Agent selected = agentSelector.select(candidates, subTask);

        if (selected == null) {
            log.warn("无合适Agent: role={}, taskId={}", role, subTask.getId());
            subTask.setStatus(SubTaskStatus.WAITING_ON_DUTY);
            subTaskService.save(subTask);
            return;
        }

        // 3. 分配任务
        subTask.setStatus(SubTaskStatus.ASSIGNED);
        subTask.setAssignedAgentId(selected.getId());
        subTask.setAssignedAt(Instant.now());
        subTaskService.save(subTask);

        // 4. 实时通知（WebSocket）
        shiftManager.notifyNewTask(
            selected.getId(),
            subTask.getId(),
            role,
            subTask.getPriority()
        );

        // 5. MQ兜底（异步通知，Agent拉取用）
        rabbitTemplate.convertAndSend(
            "agent.task.queue",
            Map.of(
                "taskId", subTask.getId(),
                "agentId", selected.getId(),
                "role", role,
                "type", "TASK_ASSIGNED"
            )
        );

        log.info("任务分配: taskId={}, agentId={}, role={}", 
            subTask.getId(), selected.getId(), role);
    }
}
```

---

## 五、心跳巡检 + 班次过期巡检（@Scheduled）

```java
@Component
@Slf4j
public class ShiftMonitorTask {

    @Autowired
    private ShiftManager shiftManager;

    @Autowired
    private ShiftService shiftService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private SubTaskService subTaskService;

    @Autowired
    private SubTaskDispatchService subTaskDispatchService;

    // 心跳巡检：每60秒
    @Scheduled(fixedRate = 60000)
    public void heartbeatCheck() {
        Instant deadline = Instant.now().minus(5, ChronoUnit.MINUTES);
        List<ShiftRecord> inactiveShifts = shiftService.findByLastHeartbeatBefore(deadline);

        inactiveShifts.forEach(shift -> {
            if (shift.getStatus() == ShiftStatus.ACTIVE) {
                log.warn("心跳超时: agentId={}, shiftId={}, lastHeartbeat={}",
                    shift.getAgentId(), shift.getShiftId(), shift.getLastHeartbeat());

                shiftManager.forceCheckOut(shift.getAgentId(), shift.getShiftId(), "HEARTBEAT_TIMEOUT");
            }
        });

        log.info("心跳巡检完成: 检查{}, 处理{}个超时班次", 
            inactiveShifts.size(), inactiveShifts.size());
    }

    // 班次过期巡检：每10分钟
    @Scheduled(fixedRate = 600000)
    public void shiftExpiryCheck() {
        Instant deadline = Instant.now().minus(8, ChronoUnit.HOURS);
        List<ShiftRecord> expiredShifts = shiftService.findByExpiresAtBeforeAndStatus(
            deadline, ShiftStatus.ACTIVE);

        expiredShifts.forEach(shift -> {
            log.warn("班次过期: agentId={}, shiftId={}, startedAt={}",
                shift.getAgentId(), shift.getShiftId(), shift.getStartedAt());

            shiftManager.forceCheckOut(shift.getAgentId(), shift.getShiftId(), "SHIFT_EXPIRED");
        });

        log.info("班次过期巡检完成: 检查{}, 处理{}个过期班次",
            expiredShifts.size(), expiredShifts.size());
    }

    // 任务分配超时巡检：每30秒
    @Scheduled(fixedRate = 30000)
    public void taskAssignmentTimeoutCheck() {
        // ASSIGNED状态超过10分钟未被claim，重新调度
        Instant deadline = Instant.now().minus(10, ChronoUnit.MINUTES);
        List<SubTask> timeoutTasks = subTaskService.findByStatusAndAssignedAtBefore(
            SubTaskStatus.ASSIGNED, deadline);

        timeoutTasks.forEach(task -> {
            log.warn("任务分配超时: taskId={}, agentId={}, assignedAt={}",
                task.getId(), task.getAssignedAgentId(), task.getAssignedAt());

            // 重置任务
            task.setStatus(SubTaskStatus.PENDING);
            task.setAssignedAgentId(null);
            subTaskService.save(task);

            // 标记Agent异常（如果还在线）
            Agent agent = agentService.getById(task.getAssignedAgentId());
            if (agent != null && agent.getStatus() == AgentStatus.ON_DUTY) {
                // 可选：标记为SUSPENDED或降低评分
                agent.setScore(agent.getScore() - 10);
                agentService.save(agent);
            }

            // 重新调度
            // 排除原Agent
            // ...
        });
    }

    // 连接健康巡检：每30秒
    @Scheduled(fixedRate = 30000)
    public void connectionHealthCheck() {
        // 检查WebSocket连接状态，清理僵尸连接
        // ...
    }
}
```

---

## 六、Python Bridge守护进程

```python
#!/usr/bin/env python3
# HelloAI Agent Bridge
# 像打卡上班一样调度AI Agent

import asyncio
import websockets
import requests
import json
import os
import sys
import time
import signal
from datetime import datetime, timedelta
from typing import Optional, Dict, Any
import argparse

class AgentBridge:
    # Agent桥接守护进程

    def __init__(self, config: Dict[str, str]):
        self.server_ws = config.get("server_ws", "wss://your-platform.com/ws/agent")
        self.server_http = config.get("server_http", "https://your-platform.com")
        self.token = config["token"]
        self.agent_id = config["agent_id"]
        self.role = config.get("role", "EXECUTOR")
        self.skills = config.get("skills", "").split(",")
        self.max_concurrent = int(config.get("max_concurrent", "2"))
        self.work_mode = config.get("work_mode", "FULL_TIME")

        self.ws = None
        self.running = True
        self.shift_id = None
        self.current_tasks = set()
        self.heartbeat_interval = 30
        self.reconnect_delay = 5

    async def run(self):
        # 主循环: 连接 -> 工作 -> 重连
        while self.running:
            try:
                # 1. 打卡上班(获取WebSocket连接)
                await self.check_in()

                # 2. 建立WebSocket连接
                await self.connect_ws()

                # 3. 工作循环(接收通知 + 心跳)
                await self.work_loop()

            except Exception as e:
                print(f"[{self.now()}] 连接异常: {e}")
                print(f"[{self.now()}] {self.reconnect_delay}秒后重连...")
                await asyncio.sleep(self.reconnect_delay)
                self.reconnect_delay = min(self.reconnect_delay * 2, 60)  # 指数退避

    async def check_in(self):
        # 打卡上班: 调用MCP checkIn工具
        print(f"[{self.now()}] 正在打卡上班...")

        payload = {
            "agentId": self.agent_id,
            "role": self.role,
            "skills": self.skills,
            "maxConcurrent": self.max_concurrent,
            "workMode": self.work_mode
        }

        try:
            resp = requests.post(
                f"{self.server_http}/api/mcp/checkIn",
                headers={"Authorization": f"Bearer {self.token}"},
                json=payload,
                timeout=10
            )

            if resp.status_code != 200:
                raise Exception(f"打卡失败: {resp.status_code} {resp.text}")

            result = resp.json()
            self.shift_id = result["shiftId"]
            self.server_ws = result["wsUrl"]
            self.heartbeat_interval = result.get("heartbeatInterval", 30)

            print(f"[{self.now()}] 打卡成功")
            print(f"  班次ID: {self.shift_id}")
            print(f"  有效期: {result['expiresAt']}")
            print(f"  心跳间隔: {self.heartbeat_interval}秒")

        except Exception as e:
            print(f"[{self.now()}] 打卡失败: {e}")
            raise

    async def connect_ws(self):
        # 建立WebSocket连接
        print(f"[{self.now()}] 正在连接WebSocket...")

        headers = {"Authorization": f"Bearer {self.token}"}

        self.ws = await websockets.connect(
            self.server_ws,
            extra_headers=headers,
            ping_interval=None,  # 自己控制心跳
        )

        print(f"[{self.now()}] WebSocket连接成功")
        self.reconnect_delay = 5  # 重置退避

    async def work_loop(self):
        # 工作循环: 接收消息 + 发送心跳
        heartbeat_task = asyncio.create_task(self.heartbeat_loop())
        receive_task = asyncio.create_task(self.receive_loop())

        try:
            await asyncio.gather(heartbeat_task, receive_task)
        except asyncio.CancelledError:
            pass
        finally:
            heartbeat_task.cancel()
            receive_task.cancel()

    async def heartbeat_loop(self):
        # 心跳循环
        while self.running:
            try:
                await asyncio.sleep(self.heartbeat_interval)

                if self.ws and self.ws.open:
                    await self.ws.send(json.dumps({
                        "type": "HEARTBEAT",
                        "shiftId": self.shift_id,
                        "timestamp": int(time.time() * 1000)
                    }))

            except Exception as e:
                print(f"[{self.now()}] 心跳异常: {e}")
                break

    async def receive_loop(self):
        # 接收消息循环
        while self.running:
            try:
                # 设置超时,用于检测连接断开
                message = await asyncio.wait_for(
                    self.ws.recv(),
                    timeout=self.heartbeat_interval + 10
                )

                msg = json.loads(message)
                await self.handle_message(msg)

            except asyncio.TimeoutError:
                print(f"[{self.now()}] 接收超时,连接可能已断开")
                break
            except websockets.exceptions.ConnectionClosed:
                print(f"[{self.now()}] WebSocket连接已关闭")
                break
            except Exception as e:
                print(f"[{self.now()}] 接收异常: {e}")
                break

    async def handle_message(self, msg: Dict[str, Any]):
        # 处理平台消息
        msg_type = msg.get("type")

        if msg_type == "SHIFT_START":
            print(f"[{self.now()}] {msg.get('message', '班次开始')}")

        elif msg_type == "NEW_TASK":
            await self.handle_new_task(msg)

        elif msg_type == "HEARTBEAT_ACK":
            # 心跳确认,无需处理
            pass

        elif msg_type == "FORCE_CHECKOUT":
            print(f"[{self.now()}] 强制下班: {msg.get('reason')}")
            await self.handle_force_checkout(msg)

        elif msg_type == "PING":
            await self.ws.send(json.dumps({"type": "PONG"}))

        else:
            print(f"[{self.now()}] 未知消息: {msg_type}")

    async def handle_new_task(self, msg: Dict[str, Any]):
        # 处理新任务通知: 拉取 -> 抢占 -> 执行
        task_id = msg["taskId"]
        priority = msg.get("priority", 0)

        print(f"[{self.now()}] 新任务通知: {task_id} (优先级: {priority})")

        # 1. 拉取任务详情
        task = await self.pull_task(task_id)
        if not task:
            print(f"[{self.now()}] 任务拉取失败或已被抢占")
            return

        # 2. 抢占任务
        if not await self.claim_task(task_id):
            print(f"[{self.now()}] 任务抢占失败")
            return

        print(f"[{self.now()}] 任务抢占成功: {task_id}")
        self.current_tasks.add(task_id)

        # 3. 执行任务(调用本地AI)
        try:
            result = await self.execute_task(task)

            # 4. 上报结果,选择继续或下班
            await self.report_task_done(task_id, result, stay_on_duty=True)

        except Exception as e:
            print(f"[{self.now()}] 任务执行异常: {e}")
            await self.report_task_blocked(task_id, str(e))

        finally:
            self.current_tasks.discard(task_id)

    async def pull_task(self, task_id: str) -> Optional[Dict]:
        # 拉取任务详情
        try:
            resp = requests.get(
                f"{self.server_http}/api/agents/me/tasks/{task_id}",
                headers={"Authorization": f"Bearer {self.token}"},
                timeout=10
            )

            if resp.status_code == 200:
                return resp.json()
            return None

        except Exception as e:
            print(f"[{self.now()}] 拉取任务失败: {e}")
            return None

    async def claim_task(self, task_id: str) -> bool:
        # 抢占任务
        try:
            resp = requests.post(
                f"{self.server_http}/api/agents/me/tasks/{task_id}/claim",
                headers={"Authorization": f"Bearer {self.token}"},
                timeout=10
            )

            if resp.status_code == 200:
                result = resp.json()
                return result.get("success", False)
            return False

        except Exception as e:
            print(f"[{self.now()}] 抢占任务失败: {e}")
            return False

    async def execute_task(self, task: Dict) -> str:
        # 执行任务: 调用本地Claude Code/Trae或其他AI工具
        task_type = task.get("type")
        task_content = task.get("content")

        print(f"[{self.now()}] 执行任务: {task_type}")
        print(f"  内容: {task_content[:100]}...")

        # 这里调用本地AI工具
        # 示例: 调用Claude Code CLI
        # result = await self.call_claude_code(task_content)

        # 模拟执行
        await asyncio.sleep(2)
        result = f"任务执行结果: {task_type} 完成"

        print(f"[{self.now()}] 任务执行完成")
        return result

    async def report_task_done(self, task_id: str, result: str, stay_on_duty: bool = True):
        # 上报任务完成
        try:
            resp = requests.post(
                f"{self.server_http}/api/mcp/taskDone",
                headers={"Authorization": f"Bearer {self.token}"},
                json={
                    "agentId": self.agent_id,
                    "taskId": task_id,
                    "stayOnDuty": stay_on_duty,
                    "result": result,
                    "status": "SUCCESS"
                },
                timeout=10
            )

            if resp.status_code == 200:
                result_data = resp.json()
                status = result_data.get("status")

                if status == "ON_DUTY":
                    print(f"[{self.now()}] 任务完成,继续接单")
                else:
                    print(f"[{self.now()}] 任务完成,已下班")
                    self.running = False

        except Exception as e:
            print(f"[{self.now()}] 上报结果失败: {e}")

    async def report_task_blocked(self, task_id: str, reason: str):
        # 上报任务阻塞
        try:
            await self.ws.send(json.dumps({
                "type": "BLOCKED",
                "taskId": task_id,
                "reason": reason,
                "timestamp": int(time.time() * 1000)
            }))

        except Exception as e:
            print(f"[{self.now()}] 上报阻塞失败: {e}")

    async def handle_force_checkout(self, msg: Dict[str, Any]):
        # 处理强制下班
        reason = msg.get("reason", "未知")
        print(f"[{self.now()}] 强制下班原因: {reason}")

        # 清理当前任务
        for task_id in list(self.current_tasks):
            print(f"[{self.now()}] 任务交回: {task_id}")
        self.current_tasks.clear()

        # 停止运行
        self.running = False

        # 关闭连接
        if self.ws and self.ws.open:
            await self.ws.close()

    async def check_out(self, reason: str = "DONE"):
        # 主动下班
        print(f"[{self.now()}] 正在下班...")

        try:
            resp = requests.post(
                f"{self.server_http}/api/mcp/checkOut",
                headers={"Authorization": f"Bearer {self.token}"},
                json={
                    "agentId": self.agent_id,
                    "shiftId": self.shift_id,
                    "reason": reason
                },
                timeout=10
            )

            if resp.status_code == 200:
                print(f"[{self.now()}] 下班成功")
            else:
                print(f"[{self.now()}] 下班请求失败: {resp.status_code}")

        except Exception as e:
            print(f"[{self.now()}] 下班请求异常: {e}")

        finally:
            self.running = False
            if self.ws and self.ws.open:
                await self.ws.close()

    def now(self) -> str:
        return datetime.now().strftime("%H:%M:%S")

    def stop(self):
        # 停止守护进程
        print(f"[{self.now()}] 收到停止信号")
        self.running = False


def main():
    parser = argparse.ArgumentParser(description="HelloAI Agent Bridge")
    parser.add_argument("--token", required=True, help="Agent Token")
    parser.add_argument("--agent-id", required=True, help="Agent ID")
    parser.add_argument("--role", default="EXECUTOR", help="Agent角色")
    parser.add_argument("--skills", default="", help="技能清单,逗号分隔")
    parser.add_argument("--max-concurrent", type=int, default=2, help="最大并发")
    parser.add_argument("--work-mode", default="FULL_TIME", help="工作模式")
    parser.add_argument("--server", default="https://your-platform.com", help="平台地址")
    parser.add_argument("--checkout", action="store_true", help="下班模式")

    args = parser.parse_args()

    config = {
        "token": args.token,
        "agent_id": args.agent_id,
        "role": args.role,
        "skills": args.skills,
        "max_concurrent": str(args.max_concurrent),
        "work_mode": args.work_mode,
        "server_http": args.server,
        "server_ws": args.server.replace("https://", "wss://").replace("http://", "ws://") + "/ws/agent"
    }

    bridge = AgentBridge(config)

    # 信号处理
    def signal_handler(signum, frame):
        if signum == signal.SIGINT or signum == signal.SIGTERM:
            asyncio.create_task(bridge.check_out("SIGNAL"))

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    if args.checkout:
        # 下班模式
        asyncio.run(bridge.check_out("MANUAL"))
    else:
        # 上班模式
        print(f"[{datetime.now().strftime('%H:%M:%S')}] HelloAI Agent Bridge 启动")
        print(f"  Agent ID: {args.agent_id}")
        print(f"  角色: {args.role}")
        print(f"  技能: {args.skills}")
        print(f"  平台: {args.server}")
        print(f"  按Ctrl+C下班
")

        asyncio.run(bridge.run())

        print(f"[{datetime.now().strftime('%H:%M:%S')}] Agent Bridge 已停止")


if __name__ == "__main__":
    main()
```

### 命令行使用

```bash
# 打卡上班
python helloai-bridge.py \
  --token temp_7a3f9e2... \
  --agent-id agent_001 \
  --role EXECUTOR \
  --skills "code-review,refactor,test-gen" \
  --max-concurrent 2 \
  --work-mode FULL_TIME \
  --server https://your-platform.com

# 下班
python helloai-bridge.py \
  --token temp_7a3f9e2... \
  --agent-id agent_001 \
  --checkout
```

---

## 七、一键安装脚本

### 7.1 Bash安装脚本(Linux/macOS)

```bash
#!/bin/bash
# install-helloai-bridge.sh
# 一键安装HelloAI Agent Bridge

set -e

TOKEN=""
AGENT_ID=""
ROLE="EXECUTOR"
SKILLS=""
MAX_CONCURRENT="2"
WORK_MODE="FULL_TIME"
SERVER="https://your-platform.com"

# 解析参数
while [[ $# -gt 0 ]]; do
  case $1 in
    --token) TOKEN="$2"; shift 2 ;;
    --agent-id) AGENT_ID="$2"; shift 2 ;;
    --role) ROLE="$2"; shift 2 ;;
    --skills) SKILLS="$2"; shift 2 ;;
    --max-concurrent) MAX_CONCURRENT="$2"; shift 2 ;;
    --work-mode) WORK_MODE="$2"; shift 2 ;;
    --server) SERVER="$2"; shift 2 ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

if [[ -z "$TOKEN" || -z "$AGENT_ID" ]]; then
  echo "错误: 必须提供 --token 和 --agent-id"
  echo "用法: curl -fsSL https://your-platform.com/install-bridge.sh | bash -s -- --token xxx --agent-id yyy"
  exit 1
fi

echo "安装 HelloAI Agent Bridge..."
echo "  Agent ID: $AGENT_ID"
echo "  角色: $ROLE"
echo "  平台: $SERVER"

# 1. 检测Python
if ! command -v python3 &> /dev/null; then
  echo "错误: 需要 Python 3.8+"
  exit 1
fi

PYTHON_VERSION=$(python3 --version 2>&1 | grep -oP '\d+\.\d+')
REQUIRED_VERSION="3.8"

if [[ $(echo "$PYTHON_VERSION < $REQUIRED_VERSION" | bc -l) -eq 1 ]]; then
  echo "错误: Python版本过低 ($PYTHON_VERSION),需要 3.8+"
  exit 1
fi

echo "Python版本: $PYTHON_VERSION"

# 2. 安装依赖
echo "安装依赖..."
pip3 install --user websockets requests 2>/dev/null || pip3 install websockets requests

# 3. 创建目录
INSTALL_DIR="$HOME/.helloai"
mkdir -p "$INSTALL_DIR"

# 4. 下载Bridge脚本
echo "下载Bridge脚本..."
curl -fsSL "$SERVER/static/helloai-bridge.py" > "$INSTALL_DIR/helloai-bridge.py"
chmod +x "$INSTALL_DIR/helloai-bridge.py"

# 5. 写入配置文件
cat > "$INSTALL_DIR/config.env" <<EOF
HELLOAI_TOKEN=$TOKEN
HELLOAI_AGENT_ID=$AGENT_ID
HELLOAI_ROLE=$ROLE
HELLOAI_SKILLS=$SKILLS
HELLOAI_MAX_CONCURRENT=$MAX_CONCURRENT
HELLOAI_WORK_MODE=$WORK_MODE
HELLOAI_SERVER=$SERVER
EOF

# 6. 创建启动脚本
cat > "$INSTALL_DIR/start.sh" <<'EOF'
#!/bin/bash
source "$HOME/.helloai/config.env"
python3 "$HOME/.helloai/helloai-bridge.py" \
  --token "$HELLOAI_TOKEN" \
  --agent-id "$HELLOAI_AGENT_ID" \
  --role "$HELLOAI_ROLE" \
  --skills "$HELLOAI_SKILLS" \
  --max-concurrent "$HELLOAI_MAX_CONCURRENT" \
  --work-mode "$HELLOAI_WORK_MODE" \
  --server "$HELLOAI_SERVER"
EOF
chmod +x "$INSTALL_DIR/start.sh"

cat > "$INSTALL_DIR/stop.sh" <<'EOF'
#!/bin/bash
pkill -f "helloai-bridge.py.*$HELLOAI_AGENT_ID"
EOF
chmod +x "$INSTALL_DIR/stop.sh"

# 7. 安装systemd服务(Linux)
if command -v systemctl &> /dev/null; then
  echo "安装systemd服务..."

  mkdir -p "$HOME/.config/systemd/user"

  cat > "$HOME/.config/systemd/user/helloai-bridge.service" <<EOF
[Unit]
Description=HelloAI Agent Bridge ($AGENT_ID)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=$HOME/.helloai/start.sh
ExecStop=$HOME/.helloai/stop.sh
Restart=always
RestartSec=5
StartLimitInterval=60s
StartLimitBurst=3
Environment=HOME=$HOME

[Install]
WantedBy=default.target
EOF

  systemctl --user daemon-reload
  systemctl --user enable helloai-bridge.service
  systemctl --user start helloai-bridge.service

  echo "systemd服务已安装"
  echo "  查看状态: systemctl --user status helloai-bridge"
  echo "  查看日志: journalctl --user -u helloai-bridge -f"
  echo "  停止服务: systemctl --user stop helloai-bridge"

# 8. macOS launchd
elif [[ "$OSTYPE" == "darwin"* ]]; then
  echo "安装launchd服务..."

  mkdir -p "$HOME/Library/LaunchAgents"

  cat > "$HOME/Library/LaunchAgents/com.helloai.bridge.$AGENT_ID.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>com.helloai.bridge.$AGENT_ID</string>
  <key>ProgramArguments</key>
  <array>
    <string>$HOME/.helloai/start.sh</string>
  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <dict>
    <key>SuccessfulExit</key>
    <false/>
    <key>Crashed</key>
    <true/>
  </dict>
  <key>StandardOutPath</key>
  <string>$HOME/.helloai/logs/stdout.log</string>
  <key>StandardErrorPath</key>
  <string>$HOME/.helloai/logs/stderr.log</string>
</dict>
</plist>
EOF

  mkdir -p "$HOME/.helloai/logs"
  launchctl load "$HOME/Library/LaunchAgents/com.helloai.bridge.$AGENT_ID.plist"

  echo "launchd服务已安装"
  echo "  查看状态: launchctl list | grep helloai"
  echo "  查看日志: tail -f $HOME/.helloai/logs/stdout.log"

else
  echo "未检测到systemd或launchd,请手动运行: $HOME/.helloai/start.sh"
fi

echo ""
echo "安装完成!"
echo "  安装目录: $INSTALL_DIR"
echo "  配置文件: $INSTALL_DIR/config.env"
echo "  启动脚本: $INSTALL_DIR/start.sh"
echo "  停止脚本: $INSTALL_DIR/stop.sh"
echo ""
echo "手动操作:"
echo "  上班: $HOME/.helloai/start.sh"
echo "  下班: pkill -f 'helloai-bridge.py.*$AGENT_ID'"
```

### 7.2 使用方式

```bash
# 平台生成安装命令,用户复制粘贴执行
curl -fsSL https://your-platform.com/install-bridge.sh | bash -s -- \
  --token temp_7a3f9e2... \
  --agent-id agent_001 \
  --role EXECUTOR \
  --skills "code-review,refactor" \
  --max-concurrent 2 \
  --work-mode FULL_TIME \
  --server https://your-platform.com
```

---

## 八、调度平台实时看板(Web界面)

### 8.1 前端Vue组件(简化版)

```vue
<template>
  <div class="dashboard">
    <h1>HelloAI AgentHub 调度中心</h1>

    <!-- 统计卡片 -->
    <div class="stats">
      <div class="stat-card on-duty">
        <div class="number">{{ stats.onDuty }}</div>
        <div class="label">在岗Agent</div>
      </div>
      <div class="stat-card working">
        <div class="number">{{ stats.working }}</div>
        <div class="label">执行中</div>
      </div>
      <div class="stat-card offline">
        <div class="number">{{ stats.offline }}</div>
        <div class="label">离线</div>
      </div>
      <div class="stat-card pending">
        <div class="number">{{ stats.pendingTasks }}</div>
        <div class="label">待分配任务</div>
      </div>
    </div>

    <!-- 角色分布 -->
    <div class="role-section">
      <h2>角色分布</h2>
      <div class="role-cards">
        <div v-for="role in roles" :key="role.name" class="role-card">
          <div class="role-header">
            <span class="role-name">{{ role.name }}</span>
            <span class="role-count">{{ role.onDuty }}/{{ role.total }}</span>
          </div>
          <div class="progress-bar">
            <div class="progress" :style="{width: role.percentage + '%'}"></div>
          </div>
          <div class="role-agents">
            <div v-for="agent in role.agents" :key="agent.id" 
                 class="agent-tag" :class="agent.status">
              {{ agent.id }}
              <span v-if="agent.status === 'WORKING'" class="task-badge">
                {{ agent.currentTasks }}
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 实时日志 -->
    <div class="log-section">
      <h2>实时日志</h2>
      <div class="log-container" ref="logContainer">
        <div v-for="log in logs" :key="log.id" class="log-line" :class="log.type">
          <span class="time">{{ formatTime(log.time) }}</span>
          <span class="badge" :class="log.type">{{ log.type }}</span>
          <span class="message">{{ log.message }}</span>
        </div>
      </div>
    </div>

    <!-- 任务队列 -->
    <div class="task-section">
      <h2>任务队列</h2>
      <table>
        <thead>
          <tr>
            <th>任务ID</th>
            <th>角色</th>
            <th>优先级</th>
            <th>状态</th>
            <th>分配Agent</th>
            <th>创建时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="task in tasks" :key="task.id" :class="task.status">
            <td>{{ task.id }}</td>
            <td>{{ task.role }}</td>
            <td>
              <span class="priority" :class="'p' + task.priority">P{{ task.priority }}</span>
            </td>
            <td>
              <span class="status-badge" :class="task.status">{{ task.status }}</span>
            </td>
            <td>{{ task.assignedAgent || '-' }}</td>
            <td>{{ formatTime(task.createdAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {
      stats: {
        onDuty: 0,
        working: 0,
        offline: 0,
        pendingTasks: 0
      },
      roles: [
        { name: 'EXECUTOR', onDuty: 0, total: 0, percentage: 0, agents: [] },
        { name: 'PLANNER', onDuty: 0, total: 0, percentage: 0, agents: [] },
        { name: 'REVIEWER', onDuty: 0, total: 0, percentage: 0, agents: [] },
        { name: 'PATROL', onDuty: 0, total: 0, percentage: 0, agents: [] }
      ],
      logs: [],
      tasks: [],
      ws: null
    }
  },

  mounted() {
    this.connectWebSocket()
    this.fetchInitialData()
  },

  beforeDestroy() {
    if (this.ws) this.ws.close()
  },

  methods: {
    connectWebSocket() {
      // 连接平台WebSocket(只读,接收状态更新)
      this.ws = new WebSocket('wss://your-platform.com/ws/dashboard')

      this.ws.onmessage = (event) => {
        const msg = JSON.parse(event.data)
        this.handleDashboardMessage(msg)
      }

      this.ws.onclose = () => {
        setTimeout(() => this.connectWebSocket(), 5000)
      }
    },

    handleDashboardMessage(msg) {
      switch (msg.type) {
        case 'AGENT_STATUS_CHANGE':
          this.updateAgentStatus(msg)
          break
        case 'TASK_UPDATE':
          this.updateTask(msg)
          break
        case 'LOG':
          this.addLog(msg)
          break
        case 'STATS_UPDATE':
          this.stats = msg.data
          break
      }
    },

    updateAgentStatus(msg) {
      const { agentId, role, status, shiftId } = msg
      const roleData = this.roles.find(r => r.name === role)
      if (roleData) {
        const agent = roleData.agents.find(a => a.id === agentId)
        if (agent) {
          agent.status = status
          agent.shiftId = shiftId
        } else {
          roleData.agents.push({ id: agentId, status, shiftId, currentTasks: 0 })
        }
        this.recalculateRoleStats(roleData)
      }
    },

    recalculateRoleStats(roleData) {
      const onDuty = roleData.agents.filter(a => a.status === 'ON_DUTY').length
      roleData.onDuty = onDuty
      roleData.total = roleData.agents.length
      roleData.percentage = roleData.total > 0 ? (onDuty / roleData.total * 100) : 0
    },

    updateTask(msg) {
      const task = this.tasks.find(t => t.id === msg.taskId)
      if (task) {
        Object.assign(task, msg.data)
      } else {
        this.tasks.unshift({ id: msg.taskId, ...msg.data })
      }
    },

    addLog(msg) {
      this.logs.push({
        id: Date.now() + Math.random(),
        time: msg.timestamp || Date.now(),
        type: msg.logType || 'INFO',
        message: msg.message
      })

      // 保持最多100条日志
      if (this.logs.length > 100) {
        this.logs = this.logs.slice(-100)
      }

      // 自动滚动到底部
      this.$nextTick(() => {
        const container = this.$refs.logContainer
        if (container) container.scrollTop = container.scrollHeight
      })
    },

    formatTime(timestamp) {
      return new Date(timestamp).toLocaleTimeString('zh-CN')
    },

    async fetchInitialData() {
      // 初始加载数据
      const resp = await fetch('/api/dashboard/stats')
      const data = await resp.json()
      this.stats = data.stats
      this.roles = data.roles
      this.tasks = data.tasks
    }
  }
}
</script>

<style scoped>
.dashboard {
  padding: 20px;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}

.stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.stat-card {
  padding: 20px;
  border-radius: 8px;
  text-align: center;
  color: white;
}

.stat-card.on-duty { background: #52c41a; }
.stat-card.working { background: #1890ff; }
.stat-card.offline { background: #d9d9d9; color: #666; }
.stat-card.pending { background: #faad14; }

.stat-card .number {
  font-size: 36px;
  font-weight: bold;
}

.stat-card .label {
  font-size: 14px;
  margin-top: 4px;
}

.role-cards {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.role-card {
  border: 1px solid #e8e8e8;
  border-radius: 8px;
  padding: 16px;
}

.role-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.role-name {
  font-weight: bold;
  font-size: 16px;
}

.role-count {
  font-size: 14px;
  color: #666;
}

.progress-bar {
  height: 8px;
  background: #f0f0f0;
  border-radius: 4px;
  overflow: hidden;
  margin-bottom: 12px;
}

.progress {
  height: 100%;
  background: #52c41a;
  transition: width 0.3s;
}

.agent-tag {
  display: inline-flex;
  align-items: center;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 12px;
  margin: 2px;
}

.agent-tag.ON_DUTY { background: #f6ffed; color: #52c41a; border: 1px solid #b7eb8f; }
.agent-tag.WORKING { background: #e6f7ff; color: #1890ff; border: 1px solid #91d5ff; }
.agent-tag.OFFLINE { background: #f5f5f5; color: #999; border: 1px solid #d9d9d9; }

.task-badge {
  background: #ff4d4f;
  color: white;
  border-radius: 10px;
  padding: 0 6px;
  font-size: 10px;
  margin-left: 4px;
}

.log-container {
  height: 300px;
  overflow-y: auto;
  background: #1e1e1e;
  border-radius: 8px;
  padding: 12px;
  font-family: 'Courier New', monospace;
  font-size: 13px;
}

.log-line {
  padding: 4px 0;
  color: #d4d4d4;
}

.log-line .time { color: #858585; margin-right: 8px; }
.log-line .badge { 
  padding: 2px 6px; 
  border-radius: 4px; 
  font-size: 11px; 
  margin-right: 8px;
}
.log-line .badge.INFO { background: #1890ff; color: white; }
.log-line .badge.WARN { background: #faad14; color: white; }
.log-line .badge.ERROR { background: #ff4d4f; color: white; }
.log-line .badge.SUCCESS { background: #52c41a; color: white; }

.task-section table {
  width: 100%;
  border-collapse: collapse;
}

.task-section th, .task-section td {
  padding: 8px 12px;
  text-align: left;
  border-bottom: 1px solid #e8e8e8;
}

.task-section th {
  background: #fafafa;
  font-weight: bold;
}

.priority {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  color: white;
}

.priority.p0 { background: #ff4d4f; }
.priority.p1 { background: #ff7a45; }
.priority.p2 { background: #faad14; }
.priority.p3 { background: #52c41a; }

.status-badge {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
}

.status-badge.ASSIGNED { background: #e6f7ff; color: #1890ff; }
.status-badge.IN_PROGRESS { background: #fff7e6; color: #faad14; }
.status-badge.COMPLETED { background: #f6ffed; color: #52c41a; }
.status-badge.BLOCKED { background: #fff2f0; color: #ff4d4f; }
.status-badge.PENDING { background: #f5f5f5; color: #999; }
</style>
```

---

## 九、总结

### 核心创新点

| 特性 | 说明 |
|------|------|
| **打卡上班模式** | Agent主动签到,平台按"在岗"状态派单,业务语义清晰 |
| **通知即时,消费自主** | WebSocket只发通知,Agent主动拉取,兼顾实时性与可靠性 |
| **班次管理** | 8小时班次+心跳巡检+超时强制下班,资源可控 |
| **一键安装** | 用户复制粘贴即可部署,零配置 |
| **实时看板** | 角色分布、任务队列、执行日志一目了然 |

### 实施优先级

| 优先级 | 模块 | 工作量 | 影响 |
|--------|------|--------|------|
| **P0** | WebSocket Server + ShiftManager | 2天 | 核心链路 |
| **P0** | Python Bridge守护进程 | 1天 | Agent接入 |
| **P1** | 心跳巡检 + 班次过期巡检 | 0.5天 | 稳定性 |
| **P1** | 一键安装脚本 | 0.5天 | 用户体验 |
| **P2** | 实时看板前端 | 1天 | 可视化 |
| **P2** | MCP工具(checkIn/checkOut/taskDone) | 0.5天 | 接口暴露 |

### 一句话定位

> **"HelloAI AgentHub: 让AI Agent像外卖骑手一样打卡上班、实时接单、完成下班——企业级Agent调度基础设施。"**
