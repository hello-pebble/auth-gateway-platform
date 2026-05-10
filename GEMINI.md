# Gemini Project Instructions: Base-Auth

이 프로젝트는 파편화된 서비스들의 인증 체계를 하나로 통합하는 통합 인증 생태계(SSO) 구축 프로젝트입니다. 모든 AI 작업은 아래 지침과 `docs/` 내의 설계 문서를 최우선으로 준수해야 합니다.

## 🏗️ Architecture & Tech Stack
- **Architecture**: MSA (Smart Gateway, Auth Server, Task, Matching, Preview)
- **Language**: Kotlin 2.2.0 (JVM 21)
- **Framework**: Spring Boot 3.5.3 + Spring Security 7.0
- **Security**: OAuth2 Authorization Server, OIDC, JWT (HttpOnly Cookie)
- **Build Tool**: Gradle (Kotlin DSL)

## 📜 Development Workflow & Phase
프로젝트는 **Phase 기반**으로 진행됩니다. 현재 작업의 맥락을 파악하기 위해 관련 문서를 먼저 확인하십시오.
- **Phase 4 (현재)**: MSA 대시보드 구축, Spring Security 7.0 마이그레이션, 트래픽 제어(가상 대기열).
- **Phase 5 (준비)**: 클라우드 배포(Render), 환경 변수 분리.

## 🛠️ Coding Standards & Conventions
- **Kotlin-First**: 기본적으로 Kotlin 관습(Idiomatic Kotlin)을 따릅니다.
- **Documentation**: 새로운 기능 추가나 아키텍처 변경 시 `docs/` 내의 관련 문서를 업데이트하고, 결정 사항은 `docs/DECISION_LOG_WHY.md`에 기록하십시오.
- **Security**: Spring Security 7.0의 최신 기능을 사용하며, 모든 인증 흐름은 OAuth2 표준을 준수해야 합니다.
- **Testing**: JUnit 5와 Mockito-Kotlin을 사용하여 단위/통합 테스트를 필수로 작성하십시오.

## 🤖 AI Interaction Guidelines
- **Context Awareness**: `docs/` 폴더 내의 `PROJECT_MANIFESTO.md`, `TRAFFIC_CONTROL_STRATEGY.md`, `SECURITY_UPGRADE_REPORT.md`는 프로젝트의 핵심 철학을 담고 있습니다. 작업을 시작하기 전 해당 문서들을 참고하십시오.
- **Intent-to-Code Bridge**: 사용자의 코드 작성 요청 시, 즉시 구현하지 말고 `docs/skills/intent-bridge.md`의 규칙에 따라 **Technical Spec(구현 명세서)**를 먼저 작성하여 제안하고 승인을 받으십시오.
- **Proposals First**: 새로운 기능이나 아키텍처 변경 요청 시, 2~3가지의 구현 방법론을 먼저 제시하십시오.
- **Surgical Updates**: 코드 변경은 최소한의 범위에서 정확하게 수행하며, 변경 후에는 반드시 프로젝트 전체의 빌드 및 테스트 상태를 확인하십시오.

## 🔍 Specialized Commands
- `/code-reviewer`: `docs/skills/cto-code-reviewer.md` 규칙에 따라 CTO 시각에서 코드를 리뷰합니다.
- `/code-report`: `docs/skills/base-auth-reporting-lead.md` 규칙에 따라 작업 결과를 전문적으로 보고합니다.
- `/spring-kotlin`: `docs/skills/spring-kotlin.md` 규칙에 따라 Kotlin + Spring Boot 코드를 작성합니다.
- `/spring-java`: `docs/skills/spring-java.md` 규칙에 따라 Java + Spring Boot 코드를 작성합니다.
- `/log-monitor`: `docs/skills/log-monitoring.md` 규칙에 따라 로깅 및 모니터링 코드를 작성합니다.
- `/context-manager`: `docs/skills/context-manager.md` 규칙에 따라 대화 내용을 요약 저장하고 초기화를 안내합니다.

---
*이 문서는 팀 전체의 공유 지침이며, 모든 AI 에이전트는 이 규칙을 기반으로 동작합니다.*
