package com.shadowfit.service.Exercise;

import com.shadowfit.dto.exercises.FastApiRequestDto;
import com.shadowfit.dto.exercises.VideoRequestDto;
import com.shadowfit.dto.exercises.session.SessionUpdateRequestDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.util.YoutubeValidator;
import com.shadowfit.grpc.*;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseReference;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.repository.ExerciseReferenceRepository;
import com.shadowfit.repository.ExercisesRepository;
import com.shadowfit.repository.MemberRepository;
import com.shadowfit.repository.SessionRepository;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExerciseAnalysisService {
    private final WebClient webClient;
    private final SessionRepository sessionRepository;
    private final ExercisesRepository exercisesRepository;
    private final MemberRepository memberRepository;
    private final SessionService sessionService;
    private final ExerciseReferenceRepository referenceRepository;

    @Value("${internal.api.token}")
    private String internalToken;

    @GrpcClient("fastapi-client")
    private ExerciseServiceGrpc.ExerciseServiceStub exerciseAsyncStub;

    /**
     * ✅ 기준 좌표 추출 (FastAPI gRPC 호출)
     */
    public void extractReferencePoses(Long exerciseId, String youtubeUrl) {
        com.shadowfit.grpc.ExtractRequest request = com.shadowfit.grpc.ExtractRequest.newBuilder()
                .setExerciseId(exerciseId)
                .setYoutubeUrl(youtubeUrl)
                .build();

        log.info("FastAPI에게 기준 좌표 추출 요청 전송 - 운동 ID: {}", exerciseId);

        exerciseAsyncStub.extractReferenceData(request, new StreamObserver<com.shadowfit.grpc.ExtractResponse>() {
            @Override
            public void onNext(com.shadowfit.grpc.ExtractResponse value) {
                log.info("FastAPI 추출 시작 응답 수신 - 운동 ID: {}", value.getExerciseId());
            }
            @Override
            public void onError(Throwable t) {
                log.error("좌표 추출 gRPC 통신 장애: {}", t.getMessage());
            }
            @Override
            public void onCompleted() {
                log.info("좌표 추출 gRPC 요청 완료");
            }
        });
    }

    /**
     * ✅ 운동 분석 시작 (핵심 로직)
     */
    @Transactional
    public Long startAnalysis(VideoRequestDto appDto, Long currentMemberId) {
        Session savedSession = sessionService.createSession(appDto, currentMemberId);
        Long sessionId = savedSession.getId();

        // 비동기로 FastAPI에 분석 요청
        this.sendAnalysisRequestToFastApi(sessionId, appDto);

        return sessionId;
    }

    /**
     * ✅ 비동기 FastAPI 전송 로직
     */
    @Async
    @Transactional(readOnly = true)
    public void sendAnalysisRequestToFastApi(Long sessionId, VideoRequestDto appDto) {
        log.info("비동기 분석 요청 시작 - 세션 ID: {}", sessionId);

        List<ExerciseReference> referencePoses = referenceRepository.findByExerciseId(appDto.getExerciseId());

        AnalyzeRequest.Builder requestBuilder = AnalyzeRequest.newBuilder()
                .setExerciseId(appDto.getExerciseId())
                .setSessionId(sessionId)
                .setReferenceSource(YoutubeValidator.extractId(appDto.getReferenceSource()));

        for (ExerciseReference ref : referencePoses) {
            requestBuilder.addReferencePoses(PoseDataRequest.newBuilder()
                    .setTimestampSec(ref.getTimestampSec())
                    .setJointCoordinates(ref.getJointCoordinates())
                    .build());
        }

        exerciseAsyncStub.startAnalysis(requestBuilder.build(), new StreamObserver<AnalyzeResponse>() {
            @Override
            public void onNext(AnalyzeResponse value) {
                log.info("FastAPI 응답 수신 - 세션: {}", value.getSessionId());
            }
            @Override
            public void onError(Throwable t) {
                log.error("gRPC 통신 장애: {}", t.getMessage());
            }
            @Override
            public void onCompleted() {
                log.info("FastAPI 전송 완료");
            }
        });
    }

    /**
     * ✅ AI 서버에 분석 중단 명령 전송
     */
    public void stopAnalysis(Long sessionId) {
        log.info("AI 서버 분석 중단 요청 전송 - sessionId: {}", sessionId);

        com.shadowfit.grpc.StopRequest request = com.shadowfit.grpc.StopRequest.newBuilder()
                .setSessionId(sessionId.intValue())
                .build();

        exerciseAsyncStub.stopAnalysis(request, new io.grpc.stub.StreamObserver<com.shadowfit.grpc.StopResponse>() {
            @Override
            public void onNext(com.shadowfit.grpc.StopResponse value) {
                log.info("AI 서버 응답: {}", value.getMessage());
            }
            @Override
            public void onError(Throwable t) {
                log.error("AI 서버 중단 실패: {}", t.getMessage());
            }
            @Override
            public void onCompleted() {}
        });
    }

    /**
     * ✅ 운동 세션 종료 로직 (DB 업데이트)
     */
    @Transactional
    public void completeSession(Long sessionId, SessionUpdateRequestDto dto) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        session.setTotalReps(dto.getTotalReps());
        session.setAvgSyncRate(java.math.BigDecimal.valueOf(dto.getAvgSyncRate()));
        session.setStatus(Status.COMPLETED);
        session.setEndTime(LocalDateTime.now());

        sessionRepository.save(session);
        log.info("세션 {} DB 업데이트 완료", sessionId);
    }

    /**
     * ✅ 구버전 WebClient 방식 (필요 시 사용)
     */
    @Transactional
    public Long sendToAnalysisServer(VideoRequestDto appDto, Long currentMemberId) {
        Member member = memberRepository.findById(currentMemberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Exercise exercise = exercisesRepository.findById(appDto.getExerciseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EXERCISE_NOT_FOUND));

        Session session = Session.builder()
                .user(member)
                .exercise(exercise)
                .referenceSource(appDto.getReferenceSource())
                .startTime(LocalDateTime.now())
                .status(Status.IN_PROGRESS)
                .build();

        Session savedSession = sessionRepository.save(session);
        return savedSession.getId();
    }
}