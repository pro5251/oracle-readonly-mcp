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

例如讀取 PACKAGE BODY：

```json
{"owner":"APP","name":"ORDER_API","type":"PACKAGE BODY","start_line":1,"limit":200}
```

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
