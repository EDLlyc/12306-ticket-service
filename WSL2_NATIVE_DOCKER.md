# WSL2 Native Docker Migration

## Goal

Move local development from:

- Windows
- WSL2
- Docker Desktop

to:

- WSL2
- Docker Engine inside WSL2

so the Spring Boot app and all middleware stay inside one Linux network environment.

## Why

This project previously hit three environment-level issues:

1. Windows reserved port range blocked `localhost:8888`
2. Docker Desktop made WSL2 and Windows disagree on `127.0.0.1`
3. RocketMQ route advertisement had to use the Windows host IP instead of a Linux-local address

Using native Docker inside WSL2 removes the second and third categories entirely.

## Current Script Support

The project now auto-detects the Docker backend:

- `desktop`: Docker Desktop backend, RocketMQ uses the Windows host IP
- `native`: Docker Engine running inside WSL2, RocketMQ uses `127.0.0.1`

Check the current mode:

```bash
./dev-mode.sh
```

Start everything:

```bash
./dev-start.sh
```

Stop everything:

```bash
./dev-stop.sh
```

## Migration Steps

1. Install Docker Engine inside your WSL2 Ubuntu distribution

Typical packages:

- `docker.io` or Docker CE
- `docker-compose-plugin`

2. Disable Docker Desktop integration for this distro

Otherwise `docker` may still point to Docker Desktop.

3. Start the Docker daemon inside WSL2

Depending on your setup:

- `sudo service docker start`
- or `sudo systemctl start docker`

4. Verify the backend switched away from Docker Desktop

```bash
./dev-mode.sh
```

Expected result:

- `Docker mode: native`
- `RocketMQ broker advertise IP: 127.0.0.1`
- `RocketMQ NameServer: 127.0.0.1:9876`

5. Start the project normally

```bash
./dev-start.sh
```

## Optional Manual Override

If you need to force a mode temporarily:

```bash
DEV_DOCKER_MODE=desktop ./dev-start.sh
DEV_DOCKER_MODE=native ./dev-start.sh
```

## Expected End State

After migration:

- Spring Boot runs inside WSL2
- MySQL / Redis / RocketMQ / Milvus run in Docker inside WSL2
- RocketMQ uses Linux-local `127.0.0.1`
- No Windows host IP routing is required for middleware access
