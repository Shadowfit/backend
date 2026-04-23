package com.shadowfit.service.Report;

import com.shadowfit.dto.report.detailreport.SessionReportResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.PoseData;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.report.Report;
import com.shadowfit.repository.PoseDataRepository;
import com.shadowfit.repository.ReportRepository;
import com.shadowfit.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportService {
    private final ReportRepository reportRepository;
    private final SessionRepository sessionRepository;
    private final PoseDataRepository poseDataRepository;

    @Transactional(readOnly = true)
    public SessionReportResponseDto getSessionReport(Long sessionId) {
        log.info("세션 리포트 생성 시작 - 세션 ID: {}", sessionId);

        // 1. 기초 세션 정보 및 운동 조회
        Session currentSession = sessionRepository.findSessionWithExerciseById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        // 2. AI 분석 리포트 엔티티 조회
        Report report = reportRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));

        // 3. 이전 동일 운동 세션 조회
        Optional<Session> lastSession = sessionRepository.findFirstByUserIdAndExerciseIdAndStatusOrderByStartTimeDesc(
                currentSession.getUser().getId(),
                currentSession.getExercise().getId(),
                Status.COMPLETED
        );

        List<PoseData> poseDataList = poseDataRepository.findBySessionIdOrderByTimestampSecAsc(sessionId);
        return buildReportResponse(currentSession, report, lastSession, poseDataList);
    }

    private SessionReportResponseDto buildReportResponse(Session session, Report report,
                                                         Optional<Session> lastSession,
                                                         List<PoseData> poseDataList) {
        SessionReportResponseDto responseDto = new SessionReportResponseDto();

        responseDto.setAvgSyncRate(session.getAvgSyncRate().intValue());
        responseDto.setTotalReps(session.getTotalReps());
        responseDto.setWorkoutMinutes(calculateDuration(session));


        responseDto.setCaloriesBurned(session.getCaloriesBurned().intValue());

        responseDto.setAiSafetyReport(report.getSummary());

        if (lastSession.isPresent()) {
            double diff = session.getAvgSyncRate().doubleValue() - lastSession.get().getAvgSyncRate().doubleValue();
            log.info("이전 대비 싱크로율 변화: {}", diff);
        }
        return responseDto;
    }

    private int calculateDuration(Session session) {
        if (session.getEndTime() == null) return 0;
        return (int) java.time.Duration.between(session.getStartTime(), session.getEndTime()).toMinutes();
    }
}