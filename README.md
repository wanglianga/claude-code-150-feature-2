# 工地临时材料领用退库损耗与班组核算平台

面向项目部的材料全链路管理平台：把 **采购入库 → 质检 → 预算 → 领料 → 退库 → 损耗 → 设计/施工变更追加领料与签证 → 盘点 → 班组月度核算（合同内/签证/返工拆账） → 财务结算/索赔** 放在同一条材料链路上，按楼栋、施工区域、工序、图纸和班组串接。

技术栈：**React 18 + Vite**（前端）、**Spring Boot 3 + Spring Security(JWT) + Spring Data JPA**（后端）、**MySQL 8**（生产库，Compose 部署）、H2（本地零依赖演示库）。

## 一、功能总览

| 角色 | 账号 | 能做什么 |
| --- | --- | --- |
| 项目经理 PM | `pm / pm123` | 查看某楼栋/工序/班组的真实材料消耗与偏差、变更签证确认、**变更追加领料审核（判定可签证/施工返工）**、核算与财务总览 |
| 仓管/材料员 | `keeper / keeper123` | 入库登记与批次质检、发料核对（含变更追加领料）、退库确认、损耗核销、盘点调账、损耗月底复盘 |
| 预算员 | `budget / budget123` | 编制预算、用实际消耗反推后续采购计划与分包结算量、确认签证、追加领料复核 |
| 财务 | `finance / finance123` | 供应商结算、班组扣款（含施工返工成本）、变更索赔申报、月度核算确认（区分合同内成本/签证成本） |
| 班组长 | `foreman1..5 / foreman123` | 按楼栋/工序/图纸申请领料（含夜间紧急）、**因设计/施工变更发起追加领料**、办理退库、查看本班组核算 |

> 防水班（卫生间防水高度调整场景）班组长：`foreman4 / foreman123`（赵磊）。

覆盖的业务场景：临时追加领料、**施工/设计变更导致材料追加（追加卷材+人工，PM 审核后生成追加领料单并绑定变更编号）**、规格领错退库、施工变更退库、瓷砖破损、砂浆过期、夜间紧急领料（先发后补）、供应商补货延迟/质量不合格、盘点账实不符。

核心口径：

- 批次现场库存 = 入库量 − 已领量 + 退库量 − 损耗量 + 盘点调整量；质检不合格/待检批次不可发料。
- 真实（净）消耗 = 已发料金额 − 完好退库金额；损坏退库不回补库存，计入班组责任损耗。
- **变更追加领料**：班组关联「设计变更签证 + 原预算 + 已领材料 + 新增需求」发起追加 → PM 审核判定成本承担方：
  - **可向甲方签证（VISA）**：追加材料 + 人工进入变更索赔清单（合同外签证成本，不计入合同内偏差）；
  - **施工返工（CREW）**：追加材料 + 人工计入责任班组月度扣款；
  - **项目承担（COMPANY）**：不索赔、不扣班组。
  审核通过自动生成 `LZ` 开头追加领料单，仓管发料后新增材料与变更编号绑定。
- 月底班组核算：预算金额、**合同内成本、签证成本（材料/人工）、施工返工成本（材料/人工）**、退库、损耗按责任归属（班组/供应商/设计变更/项目承担/不可抗力）拆分；
  **班组扣款 = 班组责任损耗（含损坏退库）+ 合同内正偏差（净消耗−预算的超耗部分）+ 施工返工成本（材料+人工）**；供应商质量损耗单列索赔，不扣班组。

## 二、一键启动（Docker Compose，推荐）

前置：安装 Docker / Docker Compose。

```bash
cd 150-feature-2-a
docker compose up -d --build
```

- 前端：http://localhost （Nginx 容器，80 端口；如被占用可改 docker-compose.yml 的端口映射）
- 后端：http://localhost:8080
- MySQL：localhost:3306（库名 `material`，用户 `material/material`，root 密码 `root`）

首次启动后端会自动建表并写入覆盖全部场景的演示数据。停止：`docker compose down`；连数据一起清除：`docker compose down -v`。

## 三、本地开发启动

### 后端（默认 H2 内存库，无需安装数据库）

```bash
cd backend
mvn spring-boot:run
# 或：mvn package && java -jar target/material-platform.jar
```

- 服务地址 http://localhost:8080 ，H2 控制台 http://localhost:8080/h2-console
  （JDBC URL：`jdbc:h2:mem:materialdb`，用户名 `sa`，密码空）
- 切换 MySQL：以 `mysql` profile 启动并提供环境变量（见 `backend/src/main/resources/application-mysql.yml`）：

```bash
SPRING_PROFILES_ACTIVE=mysql \
MYSQL_HOST=localhost MYSQL_DB=material MYSQL_USER=root MYSQL_PASSWORD=root \
mvn spring-boot:run
```

### 前端

```bash
cd frontend
npm install
npm run dev      # http://localhost:5173 ，/api 已代理到 http://localhost:8080
npm run build    # 产出 dist/，可用 npm run preview 预览
```

> 若 npm 全局缓存目录有权限问题，可指定临时缓存：`npm_config_cache=/tmp/npm-cache npm install`

## 四、推荐验证路径（登录后按角色体验）

1. **仓管 keeper**：「现场库存总览」看库存价值/预警 →「入库批次与质检」把待检批次判合格（补货批次可判不合格）。
2. **班组长 foreman5（水电班孙明）**：「领料单」可见一张"夜间紧急"单；也可新建领料申请。
3. **仓管 keeper**：「领料单 → 核对发料」核对预算量/已领量/现场库存，选择批次、填实发数量后发料（超预算/库存不足会拦截或预警）；再到「退库单」确认规格领错/变更退库（完好回补库存）与损坏退库（班组责任）。
4. **变更追加领料主线（本题）**：
   - **项目经理 pm**：「变更追加领料」可见两张待审核单（防水班赵磊发起）：
     - `ZJ20261003002`：设计临时调整 2#楼卫生间防水上翻高度 300mm→1800mm，追加 SBS 卷材 80㎡ + 人工 2400 元，关联签证 `QZ20261003003`、原预算 400㎡、已领 120㎡——审核确认**可向甲方签证**，系统生成 `LZ` 追加领料单；
     - `ZJ20261003003`：立面卷材搭接不达标铲除返工，追加卷材 60㎡ + 人工 1800 元——审核判定**施工返工**，成本计入防水班。
   - 审核弹窗可查看「关联变更签证、原预算量、已领材料、现场库存、新增需求」核对上下文。
   - **仓管 keeper**：对生成的追加领料单选批次发料，系统把新增材料与变更编号绑定。
   - 已发料样例 `ZJ20261003001`（签证 `QZ20261003002`，卷材 120㎡ 3840 元 + 人工 3600 元）可直接查看。
5. **仓管 keeper**：「损耗登记」登记瓷砖破损（班组）、砂浆过期（班组）、供应商质量问题；「盘点与账实调整」确认盘点单调账；「损耗月底复盘」查看按设计变更/施工错误/保管不当/供应商质量归类的损耗结构。
6. **项目经理 pm**：「楼栋消耗与偏差」按 1#楼/工序/班组过滤查看真实消耗与超耗；「变更签证」查看签证与追加领料单的绑定关系。
7. **预算员 budget**：「采购计划反推」查看建议采购量、补货延迟风险与分包结算复核口径。
8. **财务 finance**：「班组月度核算」选 2026-10 一键生成全部班组核算单并确认——防水班可看到合同内成本 10310、签证材料 6400/人工 6000、返工材料 1920/人工 1800（计入扣款）、对甲方索赔 18800；「财务结算与索赔」查看成本口径拆分、变更追加领料台账、供应商应付（扣不合格批次）、班组扣款、签证索赔。

## 五、主要 HTTP 接口（JWT：`Authorization: Bearer <token>`）

| 模块 | 接口 |
| --- | --- |
| 认证 | `POST /api/auth/login` |
| 元数据 | `GET /api/meta/options`（材料/供应商/班组/楼栋/工序下拉） |
| 库存/批次 | `GET /api/inventory`、`GET /api/batches`、`POST /api/batches/inbound`、`PUT /api/batches/{id}/qc` |
| 预算 | `GET/POST /api/budgets` |
| 领料 | `GET /api/requisitions`、`POST /api/requisitions`、`GET /api/requisitions/{id}/precheck`、`PUT /api/requisitions/{id}/issue`、`PUT /api/requisitions/{id}/reject` |
| 变更追加领料 | `GET /api/supplements`、`POST /api/supplements`、`GET /api/supplements/{id}`、`GET /api/supplements/{id}/context`、`PUT /api/supplements/{id}/review` |
| 退库 | `GET/POST /api/returns`、`PUT /api/returns/{id}/confirm`、`PUT /api/returns/{id}/reject` |
| 损耗 | `GET/POST /api/wastes` |
| 变更签证 | `GET/POST /api/visas`、`PUT /api/visas/{id}/confirm`、`PUT /api/visas/{id}/claim` |
| 盘点 | `GET/POST /api/stock-checks`、`PUT /api/stock-checks/{id}/adjust` |
| 月度核算 | `GET /api/settlements`、`POST /api/settlements/generate`、`PUT /api/settlements/{id}/confirm` |
| 分析 | `GET /api/analytics/consumption`、`/waste-review`、`/procurement-plan`、`/finance` |

curl 示例：

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"keeper","password":"keeper123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl -s http://localhost:8080/api/inventory -H "Authorization: Bearer $TOKEN"
```

## 六、测试

```bash
cd backend
mvn test        # 全链路集成测试（H2 + 种子数据，见 MaterialFlowIntegrationTest）
```

测试覆盖：登录鉴权与角色权限（401/403）、发料前预算/已领/库存核对、库存不足拦截、夜间紧急发料、规格领错退库回补、盘点调账、月底核算金额口径（领用 4460、退库 245、净消耗 4215、偏差 −6385）、损耗复盘四类原因、供应商质量索赔、采购计划反推；
以及**变更追加领料专项**（`SupplementFlowIntegrationTest`）：PM 审核上下文（关联签证/原预算 400/已领 120/库存）、可签证追加单审核→生成 LZ 追加领料单→仓管发料并绑定变更编号、施工返工追加单成本计入班组、驳回不生成单据、非法承担方 400、越权 403、月底核算三类成本拆分（合同内 10310 / 签证材料 6400 + 人工 6000 / 返工材料 1920 + 人工 1800 / 扣款 3720 / 索赔 18800）、财务三类成本与追加索赔台账。

## 七、工程结构

```
150-feature-2-a/
├── docker-compose.yml         # mysql + backend(spring) + frontend(nginx)
├── backend/                   # Spring Boot 3 / Java 17
│   ├── Dockerfile
│   └── src/main/java/com/site/material/
│       ├── domain/            # 15 个实体（批次/预算/领料/退库/损耗/签证/
│       │                      #   盘点/核算/变更追加 MaterialSupplement…）
│       ├── repo/ service/ controller/
│       ├── security/          # JWT + 五种角色
│       └── config/DataInitializer.java   # 演示种子数据（全部场景，含卫生间防水高度调整）
└── frontend/                  # React 18 + Vite
    ├── Dockerfile nginx.conf
    └── src/pages/             # 15 个角色化页面（含 Supplements 变更追加领料）
```

## 八、已执行的验证结论

- 后端 `mvn package` 编译通过；`mvn test` 集成测试 **2 个用例、全部通过**（H2 内存库）。
- 后端 jar 以默认 H2 配置实际启动成功，经 curl 实测：追加单列表/审核上下文、PM 审核（VISA/CREW 分流）生成追加领料单、仓管发料绑定签证编号、防水班月底核算（合同内 10310、签证材料 6400/人工 6000、返工材料 1920/人工 1800、扣款 3720、索赔 18800）、财务汇总（签证成本 12400、返工成本 3720、追加/签证台账）均返回正常。
- 前端 `npm run build`（Vite）构建成功，49 个模块转译通过，产出 dist 静态资源。
- Docker Compose 配置已按多阶段构建编写；当前沙箱环境无 Docker 守护进程，未在本机实际执行 `docker compose up`，请在有 Docker 的机器上按第二节命令验证。
