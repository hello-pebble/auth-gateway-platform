package com.pebble.task.config;

import com.pebble.task.domain.DateSchedule;
import com.pebble.task.domain.DateScheduleRepository;
import com.pebble.task.domain.Task;
import com.pebble.task.domain.TaskRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.LocalDateTime;
import java.util.List;

@Configuration
@Profile("!test")
public class TestDataLoader {

    @Bean
    CommandLineRunner initTaskData(TaskRepository taskRepository, DateScheduleRepository dateScheduleRepository) {
        return args -> {

            // 회원별 태스크 데이터 (username, title, description, daysFromNow, completed)
            record TaskSeed(String user, String title, String desc, int days, boolean done) {}

            List<TaskSeed> seeds = List.of(
                new TaskSeed("user",    "Render 배포 확인",        "모든 모듈 정상 동작 체크",           1,  false),
                new TaskSeed("user",    "코드 리뷰 완료",           "PR #42 머지 전 리뷰",               0,  true),
                new TaskSeed("alice",   "팀 미팅 준비",             "주간 회의 아젠다 정리",               2,  false),
                new TaskSeed("alice",   "API 문서 작성",            "Swagger 명세 업데이트",             -1, true),
                new TaskSeed("alice",   "단위 테스트 작성",          "커버리지 80% 목표",                  3,  false),
                new TaskSeed("bob",     "버그 수정 #155",           "로그인 세션 만료 이슈",               1,  false),
                new TaskSeed("bob",     "배포 파이프라인 점검",       "CI/CD 스크립트 업데이트",            -2, true),
                new TaskSeed("charlie", "DB 마이그레이션 계획",      "PostgreSQL 스키마 변경안 작성",       5,  false),
                new TaskSeed("charlie", "성능 프로파일링",           "응답시간 P99 측정",                  0,  false),
                new TaskSeed("diana",   "보안 취약점 스캔",          "OWASP Top 10 점검",                 3,  false),
                new TaskSeed("diana",   "의존성 업그레이드",         "Spring Boot 최신 버전 적용",         -3, true),
                new TaskSeed("evan",    "신규 기능 기획서 검토",      "매칭 알고리즘 개선안",                7,  false),
                new TaskSeed("evan",    "모니터링 대시보드 구성",     "Grafana 알림 설정",                  2,  false),
                new TaskSeed("fiona",   "UX 리뷰 미팅",             "프리뷰 서비스 개선 논의",             1,  false),
                new TaskSeed("fiona",   "디자인 시스템 문서화",      "컴포넌트 가이드라인 작성",            -1, true),
                new TaskSeed("grace",   "고객 피드백 분석",          "NPS 설문 결과 정리",                  2,  false),
                new TaskSeed("grace",   "A/B 테스트 결과 검토",      "전환율 비교 보고서",                 -2, true),
                new TaskSeed("henry",   "인프라 비용 최적화",        "클라우드 리소스 사용량 분석",          4,  false),
                new TaskSeed("henry",   "장애 대응 매뉴얼 업데이트",  "On-call 가이드 최신화",              0,  false),
                new TaskSeed("irene",   "채용 면접 준비",            "기술 면접 질문지 작성",               3,  false),
                new TaskSeed("irene",   "온보딩 문서 정리",          "신규 입사자 가이드 업데이트",         -1, true),
                new TaskSeed("james",   "분기 목표 설정",            "OKR 작성 및 팀 공유",                5,  false),
                new TaskSeed("james",   "레거시 코드 리팩토링",       "auth 모듈 구조 개선",                0,  false),
                new TaskSeed("admin",   "시스템 모니터링",            "트래픽 및 에러 로그 확인",            1,  false)
            );

            seeds.forEach(s -> {
                if (taskRepository.findAllByUserId(s.user()).isEmpty() ||
                        taskRepository.findAllByUserId(s.user()).stream().noneMatch(t -> t.getTitle().equals(s.title()))) {
                    taskRepository.save(Task.builder()
                            .userId(s.user())
                            .title(s.title())
                            .description(s.desc())
                            .deadline(s.days() != 0 ? LocalDateTime.now().plusDays(s.days()) : null)
                            .completed(s.done())
                            .build());
                }
            });

            // 데이트 일정 (matched pair 기준)
            record ScheduleSeed(String host, String guest, String matchId, String title, String location, int days) {}

            List<ScheduleSeed> schedules = List.of(
                new ScheduleSeed("alice",   "bob",     "match_001", "첫 번째 데이트 ☕",  "서울 성수동 카페",  3),
                new ScheduleSeed("charlie", "diana",   "match_002", "영화 관람 🎬",       "CGV 강남",         5),
                new ScheduleSeed("evan",    "fiona",   "match_003", "한강 피크닉 🌸",     "한강공원 여의도",   7),
                new ScheduleSeed("grace",   "henry",   "match_004", "저녁 식사 🍽",       "이태원 레스토랑",   4),
                new ScheduleSeed("irene",   "james",   "match_005", "전시회 관람 🎨",     "국립현대미술관",    6),
                new ScheduleSeed("user",    "alice",   "match_006", "스터디 카페 📚",     "강남 스터디카페",   2)
            );

            schedules.forEach(s -> {
                boolean exists = !dateScheduleRepository
                        .findAllByHostUserIdOrGuestUserId(s.host(), s.host()).isEmpty();
                if (!exists) {
                    dateScheduleRepository.save(DateSchedule.builder()
                            .hostUserId(s.host())
                            .guestUserId(s.guest())
                            .matchingId(s.matchId())
                            .title(s.title())
                            .description(s.host() + "와 " + s.guest() + "의 약속")
                            .meetingAt(LocalDateTime.now().plusDays(s.days()))
                            .location(s.location())
                            .status("SCHEDULED")
                            .build());
                }
            });

            System.out.println(">>> Task Module Test Data Initialized (24 tasks + 6 date schedules)");
        };
    }
}
