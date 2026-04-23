package com.shadowfit.dto.report;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SessionReportResponseDto {
    private Long sessionId;

    // 상단 4개 요약 카드용
    private int avgSyncRate;             // 89 (%)
    private int totalReps;               // 156
    private int workoutMinutes;          // 35
    private int caloriesBurned;          // 250

    // 종목별 싱크로율 리스트 (디자인의 프로그레스 바 영역)
    private List<ExerciseSyncRateDto> syncRateDetails;

    // Worst 구간 분석 (디자인의 노란색 경고창 영역)
    private WorstSectionDto worstSection;

    // AI 안전 리포트 (하단 텍스트 영역)
    private String aiSafetyReport;       // "페이스풀 시 승모근이 과도하게..."
}
