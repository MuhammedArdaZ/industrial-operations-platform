# ADR-011: Cloud Deployment Architecture & Container Orchestration (AWS EC2)

## Context

Following the local verification of the modular monolith and its supporting distributed services (PostgreSQL, Eclipse Mosquitto, Apache Kafka KRaft, Spring Boot Backend, Prometheus, Grafana), the platform required a real cloud deployment to validate operational behavior under continuous 24/7 telemetry streaming.

The deployment needed to satisfy the following requirements:
1. **Zero Architectural Divergence:** The cloud environment must run the exact same containerized software architecture as local development.
2. **Minimal Operational Complexity & Cost:** Avoid premature cluster orchestration (Kubernetes, AWS EKS) or high-cost managed services (AWS MSK for Kafka, AWS RDS) that exceed the scope of an educational/demonstrative project.
3. **Reproducibility:** A clean, automated deployment process driven by containerization without manual host configuration.

## Decision

1. **Deploy via Docker Compose on AWS EC2:**
   - Provision an **AWS EC2** instance (`m7i-flex.large`, 2 vCPU, 8 GB RAM, 20 GB gp3 storage running Ubuntu 24.04 LTS).
   - Orchestrate the entire platform stack using Docker Compose:
     - `industrial-postgres` (PostgreSQL 16)
     - `industrial-mosquitto` (Eclipse Mosquitto MQTT broker)
     - `industrial-kafka` (Apache Kafka 3.7 KRaft mode)
     - `industrial-backend` (Spring Boot 3.5 multi-stage Docker build)
     - `industrial-prometheus` (Prometheus v2.51)
     - `industrial-grafana` (Grafana 10.4 with pre-provisioned dashboards)
     - `sensor-simulator` (Python sensor simulator profile running continuously)

2. **Self-Contained Multi-Stage Docker Build:**
   - Migrate `backend/Dockerfile` to a multi-stage Maven build (`eclipse-temurin:25-jdk-alpine` builder stage, `eclipse-temurin:25-jre-alpine` runtime stage).
   - Eliminates the need to install JDK 25 or Maven on the cloud host, isolating compilation dependencies inside the container engine.

3. **Dynamic Public IP vs Elastic IP (Cost Decision):**
   - An AWS Elastic IP (static public IP) was deliberately not provisioned. In AWS, idle or stopped instances with associated Elastic IPs incur separate hourly charges.
   - The instance uses AWS dynamic public addressing. Consequently, stopping and starting the instance allocates a new public IP address. Documentation and CI references denote the cloud host dynamically as `<EC2_PUBLIC_IP>`.

4. **Security Group Configuration & Access Policy:**
   - Ingress ports opened for operational access:
     - Port 22: SSH (keypair authentication via `industrial-key.pem`)
     - Port 8080: Spring Boot REST API & Actuator endpoints
     - Port 3000: Grafana visualization dashboard (`admin` / `admin`)
   - Internal ports (5432 PostgreSQL, 1883 MQTT, 9092 Kafka, 9090 Prometheus) communicate securely across the Docker internal bridge network.

## Alternatives Considered

- **AWS Managed Services (RDS, MSK, ECS/Fargate):**
  - Rejected due to high ongoing financial cost and operational friction. A single-node EC2 instance hosting Docker Compose fulfills all platform operational verification goals at a fraction of the cost.
- **Kubernetes (Minikube / EKS):**
  - Rejected in accordance with ADR-001. Introducing Kubernetes before the platform is decomposed into microservices introduces needless complexity without tangible benefits.

## Consequences

- **Cost Efficiency:** EC2 `m7i-flex.large` can be stopped when inactive, incurring zero vCPU/RAM charges (only minimal EBS disk preservation cost).
- **Public IP Volatility:** The public IP changes upon instance restart; documentation must reflect dynamic IP access rather than assuming permanent static IPs.
- **Security Hardening (Phase 5):** Admin endpoints (DLT replay, alert acknowledge) and Grafana credentials currently run with demonstration defaults and should be placed behind an API gateway or VPN in production environments.
