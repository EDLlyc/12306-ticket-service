
# MySQL 基础练习

这个目录用于单独练习 MySQL 基础操作，不影响项目业务库 `db_12306`。练习脚本默认使用数据库 `mysql_practice`。

## 连接方式

先启动项目里的 MySQL：

```bash
docker compose -f docker-compose.dev.yml up -d mysql
```

默认连接信息：

```text
host: 127.0.0.1
port: 23306
user: root
password: 123456
database: mysql_practice
```

进入 MySQL 命令行：

```bash
mysql -h 127.0.0.1 -P 23306 -uroot -p123456
```

如果你本机没有安装 `mysql` 客户端，也可以进入容器执行：

```bash
docker exec -it ticket-mysql mysql -uroot -p123456
```

## 初始化练习库

在项目根目录执行：

```bash
mysql -h 127.0.0.1 -P 23306 -uroot -p123456 < mysql-practice/00_reset_schema.sql
```

或使用容器内客户端：

```bash
docker exec -i ticket-mysql mysql -uroot -p123456 < mysql-practice/00_reset_schema.sql
```

每次想从干净数据重新开始，都可以重复执行 `00_reset_schema.sql`。

## 文件说明

- `00_reset_schema.sql`：删除并重建 `mysql_practice`，创建练习表并插入样例数据。
- `01_select_basics.sql`：基础查询、排序、分页、条件过滤。
- `02_insert_update_delete.sql`：插入、更新、删除和事务回滚练习。
- `03_joins_grouping.sql`：多表连接、聚合统计、分组筛选。
- `04_transactions_indexes.sql`：事务、锁、索引和执行计划入门。

建议顺序：先执行 `00_reset_schema.sql`，再打开 `01` 到 `04` 逐条运行和改写。
