# Oracle Read-only MCP

供 AI CLI 查詢 Oracle TABLE 與 SP／PACKAGE 原始碼的 MCP server。使用官方 MCP SDK 與 Oracle JDBC 驅動，工具與唯讀限制為本專案自訂實作，並非 Oracle 官方 SQLcl MCP Server。

## 需求與安裝

- Node.js 22 以上。
- JDK 17 以上（需要 `java` 與 `javac`）。
- 自行取得合法授權的 Oracle JDBC driver，例如 SQLcl 內附的 `ojdbc11.jar`。本 repo 不包含 JDBC、SQLcl 或 Java 二進位檔。
- 可連線的 Oracle 資料庫；前身實作曾在 Oracle 11.2.0.4.0 實測，其他版本須自行驗證。

```bash
npm ci --ignore-scripts
npm run build
npm test
```

若 Java 不在 PATH，先設定 `JAVA_HOME`；亦可分別設定 `ORACLE_MCP_JAVA` 與 `ORACLE_MCP_JAVAC`。

## 連線設定

複製 `examples/oracle-config.example.json` 到 repo 以外的私人目錄，填入專用唯讀帳號與連線資訊。例如：

```json
{
  "user": "YOUR_READONLY_USER",
  "password": "SET_LOCALLY_DO_NOT_COMMIT",
  "connectString": "//db.example.com:1521/YOUR_SERVICE"
}
```

`connectString` 也可使用 Oracle `(DESCRIPTION=...)` descriptor。密碼可包含分號或等號；設定使用 JSON，不解析 .NET connection string。

Linux／macOS 可用 `chmod 600 /private/path/oracle.json` 限制檔案權限；Windows 請設定相應的檔案 ACL。勿將真正的連線設定提交到 Git。

啟動前設定：

```bash
export ORACLE_CONFIG_FILE=/private/path/oracle.json
export ORACLE_JDBC_JAR=/path/to/ojdbc11.jar
npm start
```

服務使用 stdio；由 MCP client 啟動並管理生命週期。憑證透過子程序 stdin 傳給 JDBC runner，不放在 Java 命令列。

## Codex CLI

先完成安裝與編譯，再將以下路徑換成實際的絕對路徑：

```bash
codex mcp add oracle_readonly \
  --env ORACLE_CONFIG_FILE=/private/path/oracle.json \
  --env ORACLE_JDBC_JAR=/path/to/ojdbc11.jar \
  --env ORACLE_MCP_JAVA=/path/to/java \
  -- node /path/to/oracle-readonly-mcp/server.mjs

codex mcp get oracle_readonly
```

新啟動的 Codex session 會載入設定。MCP client 若使用 JSON 設定，可參考：

```json
{
  "mcpServers": {
    "oracle_readonly": {
      "command": "node",
      "args": ["/path/to/oracle-readonly-mcp/server.mjs"],
      "env": {
        "ORACLE_CONFIG_FILE": "/private/path/oracle.json",
        "ORACLE_JDBC_JAR": "/path/to/ojdbc11.jar",
        "ORACLE_MCP_JAVA": "/path/to/java"
      }
    }
  }
}
```

## 工具

| 工具 | 功能 |
| --- | --- |
| `database_info` | 讀取資料庫版本，需要讀取 `V$VERSION` 的權限 |
| `list_objects` | 依 owner、type、LIKE pattern 查詢可存取物件，最多 500 筆 |
| `table_columns` | 欄位名稱、型別、長度、nullable |
| `table_constraints` | 約束、欄位、參照的約束 |
| `procedure_source` | SP／FUNCTION／PACKAGE 等原始碼，每次最多 1000 行，可用 start_line 分頁 |
| `procedure_arguments` | SP／PACKAGE 的參數，可用 routine 篩選 PACKAGE 成員 |
| `table_rows` | 一般實體資料表的資料樣本，預設 20 筆、最多 100 筆 |

先使用 `list_objects` 找到 owner，再傳給其他工具；登入帳號可能沒有自己的物件。未提供 owner 時，其他工具預設使用登入帳號。

## 讀取 SP、函式與 Table 欄位結構

以下範例是 MCP 工具名稱與 arguments；請替換 owner、name 為實際名稱。工具只讀取內容，不執行 SP 或函式。

### 先查物件名稱與 owner

`list_objects` 可依物件種類與名稱篩選。登入帳號可能沒有自己的物件，因此建議先找出真正的 owner。

```json
{"name":"list_objects","arguments":{"type":"PROCEDURE","pattern":"%ORDER%","limit":100}}
```

查函式或資料表時，將 type 改為 `FUNCTION` 或 `TABLE`。名稱篩選使用 SQL LIKE 的 `%` 與 `_` 萬用字元。

### 方法一：讀取 SP 原始碼

呼叫 `procedure_source`，type 指定 `PROCEDURE`。

```json
{"name":"procedure_source","arguments":{"owner":"APP","name":"GET_ORDER","type":"PROCEDURE","start_line":1,"limit":200}}
```

回傳欄位為 `OWNER`、`NAME`、`TYPE`、`LINE`、`TEXT`，按行號排序。超過 200 行時，下一次將 start_line 設為 201；持續讀取至沒有資料。每次 limit 上限為 1000。

Codex 提問範例：「使用 Oracle 唯讀 MCP，讀取 APP.GET_ORDER 的 SP 原始碼，分頁讀取全部內容，不執行它。」

### 方法二：讀取函式程式碼

使用同一個 `procedure_source` 工具，type 改為 `FUNCTION`。

```json
{"name":"procedure_source","arguments":{"owner":"APP","name":"CALCULATE_AMOUNT","type":"FUNCTION","start_line":1,"limit":200}}
```

如果函式位於 PACKAGE 內，name 應填 PACKAGE 名稱，type 使用 `PACKAGE` 讀宣告、`PACKAGE BODY` 讀實作；再從回傳文字定位成員函式。它不是獨立的 FUNCTION 物件。

```json
{"name":"procedure_source","arguments":{"owner":"APP","name":"ORDER_API","type":"PACKAGE BODY","start_line":1,"limit":200}}
```

Codex 提問範例：「讀取 APP.CALCULATE_AMOUNT 的函式程式碼並解釋計算邏輯，不呼叫函式。」

### 方法三：讀取 Table 欄位結構

呼叫 `table_columns`，依欄位順序回傳：

```json
{"name":"table_columns","arguments":{"owner":"APP","name":"ORDERS"}}
```

| 回傳欄位 | 說明 |
| --- | --- |
| COLUMN_ID | 欄位順序 |
| COLUMN_NAME | 欄位名稱 |
| DATA_TYPE | Oracle 資料型別 |
| DATA_LENGTH | 儲存長度，單位為 bytes |
| DATA_PRECISION | 數值精度 |
| DATA_SCALE | 數值小數位數 |
| NULLABLE | Y 表示允許 NULL，N 表示不允許 |

主鍵、外鍵、唯一約束等資訊使用 `table_constraints` 補查：

```json
{"name":"table_constraints","arguments":{"owner":"APP","name":"ORDERS"}}
```

Codex 提問範例：「列出 APP.ORDERS 的欄位名稱、型別、長度、是否允許 NULL，以及主鍵與外鍵，不讀取資料列。」

### 回傳空白與權限

原始碼由 `ALL_SOURCE` 讀取，欄位由 `ALL_TAB_COLUMNS` 讀取，只能看到連線帳號可存取的資訊。結果空白不一定表示物件不存在；先確認 owner、name、type 與帳號權限。對 synonym，先找到實際物件的 owner 與名稱。Wrapped 原始碼不會被解密。

名稱限定一般未加引號的 Oracle 識別字，會轉大寫；不支援 database link 或含 schema 的複合 name，請分開提供 owner 與 name。

資料列順序不保證；只回傳支援的實體純量欄位，排除 hidden、virtual、LOB、RAW、物件型別。VIEW 可查欄位，不能讀取資料列。Wrapped 原始碼仍維持 wrapped。

單一欄位最多 4096 字元，結果約 100 萬字元／2000 筆上限，觸及上限會標示 `truncated`。`truncated: false` 不代表超出工具 limit 的資料已全部取得。

## 唯讀邊界

- Node 與 JDBC runner 使用固定工具清單，不接受任意 SQL、PL/SQL、WHERE／欄位表達式或連線憑證覆寫。
- 值使用 PreparedStatement 綁定；動態表名與欄名驗證後加雙引號。
- 資料列查詢排除 VIEW、SYNONYM、external／object／temporary table 與虛擬欄位。
- 每次連線先執行 `SET TRANSACTION READ ONLY`，最後 rollback，不提供 commit、DML、DDL 或 SP 執行工具。
- 不建立資料庫紀錄表；資料庫本身已有的登入 trigger、VPD policy 與稽核機制仍由資料庫控制。

這是 MCP 工具層的限制，不會修改 Oracle 帳號權限，也無法約束其他程式使用同一帳號。建議由 DBA 提供專用帳號，僅授予所需資料的查詢與資料字典存取權限。唯讀交易不能代替權限隔離。

## 原始碼與測試

- `server.mjs`：MCP 介面、參數驗證與 JDBC 子程序管理。
- `OracleReadOnly.java`：固定查詢與資料庫連線。
- `server.test.mjs`：非法工具、SQL 注入參數、查詢上限與憑證錯誤訊息測試，不需資料庫。
- `scripts/build.mjs`：跨平台 Java 編譯，輸出到忽略的 `build/`。

`npm test` 不會連線資料庫，也不會以實際 DML／DDL 試探權限。資料庫相容性與帳號可見範圍須另做整合驗證。

## 上游依賴

- [Model Context Protocol TypeScript SDK](https://github.com/modelcontextprotocol/typescript-sdk)：MIT。
- [Zod](https://github.com/colinhacks/zod)：MIT。
- [Oracle JDBC](https://www.oracle.com/database/technologies/appdev/jdbc.html)：自行安裝，依所選版本的授權條款使用。

本 repo 尚未指定原始碼授權條款；上游依賴各自的授權不會自動適用於本專案。
