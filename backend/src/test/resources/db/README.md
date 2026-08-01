# 为什么这里有一份 SQL 副本

`docker-compose.yml` 里 backend 容器只挂载了 `./backend:/workspace`，仓库根目录的 `db/`
在容器内**不可见**。集成测试（`npm run test:backend` → `docker compose run --no-deps --rm backend mvn test`）
必须在容器内重建 `xiyiyun_test` 库结构，因此这里保存了 `db/init/001_schema.sql` 与
`db/migrations/*.sql` 的**逐字节副本**，通过 classpath 读取。

## 与源文件保持同步

`CHECKSUMS.txt` 记录了复制时刻源文件的 SHA-256。改动 `db/` 下任何脚本后，在宿主机执行：

```bash
cp db/init/001_schema.sql backend/src/test/resources/db/init/
cp db/migrations/*.sql   backend/src/test/resources/db/migrations/
(cd . && shasum -a 256 db/init/001_schema.sql db/migrations/*.sql) > backend/src/test/resources/db/CHECKSUMS.txt
```

漂移自检（宿主机，任何时候都可以跑）：

```bash
shasum -a 256 -c backend/src/test/resources/db/CHECKSUMS.txt
```

`SchemaStackingTest` 会在容器内校验 classpath 副本与 `CHECKSUMS.txt` 一致，
即副本自身未被篡改；源文件是否变更需靠上面这条宿主机命令确认。
