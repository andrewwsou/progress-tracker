# Running the services without Docker

The quickest way to run everything is `docker compose up --build --wait` (see the [README](../README.md)).
This is the manual setup, for working on one service at a time.

Requires Postgres 14+, Java 17, and Node 22.13+ (or 24+).

1. **Database**
   ```
   createuser ptrack --pwprompt   # password: ptrack
   createdb progresstracker -O ptrack
   ```
2. **API** (from `backend/progresstracker`). It creates the schema on startup, so start it before the worker.
   ```
   export JWT_SECRET=$(openssl rand -base64 48)
   ./mvnw spring-boot:run
   ```
   Runs fully synchronously by default, with no AWS credentials needed. The API refuses to start
   without a `JWT_SECRET` of at least 32 bytes. To exercise the async path against a real SQS
   queue, set `QUEUE_ENABLED=true`, `QUEUE_SQS_URL`, and AWS credentials.
3. **Worker** (from `backend/progress-worker`). It writes the weekly summaries in every mode and
   applies rewards in async mode.
   ```
   ../progresstracker/mvnw -f pom.xml spring-boot:run
   ```
   With `QUEUE_ENABLED` unset the worker does not poll the queue but still runs the weekly-summary
   job. For async mode, start it with the same queue settings as the API:
   ```
   QUEUE_ENABLED=true QUEUE_SQS_URL=<queue url> ../progresstracker/mvnw -f pom.xml spring-boot:run
   ```
4. **Frontend** (from `frontend`)
   ```
   npm install && npm run dev
   ```
   Serves on `http://localhost:5173` and calls the API at `http://localhost:8080`; set
   `VITE_API_URL` to point it elsewhere.

**Configuration.** No secrets live in the repo. Every deployment-specific value (JWT secret,
database credentials, queue URL, automation token) is an environment variable with a
local-dev default where one is safe; [`.env.example`](../.env.example) lists them all.

## The Docker Compose stack

The API listens on `http://localhost:8080` (loopback only). If that port is taken, start the
stack with `API_PORT=8081`, run the smoke test with `BASE_URL=http://localhost:8081`, and start the
UI with `VITE_API_URL=http://localhost:8081`.

Nothing in the stack talks to AWS: the queue is [ElasticMQ](https://github.com/softwaremill/elasticmq),
configured in [`infra/local/elasticmq.conf`](../infra/local/elasticmq.conf) with the same
dead-letter policy as the Terraform in `infra/terraform/sqs.tf`. The services reach it through
`QUEUE_ENDPOINT_OVERRIDE`, which is left blank in production so the AWS SDK resolves the real SQS
endpoint.
