package com.shadowfit.controller;

import com.shadowfit.dto.exercises.VideoRequestDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.Exercise.ExerciseAnalysisService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name="운동 분석", description="기준 영상 좌표 다운로드/업로드")
@Slf4j
@RestController
@RequestMapping("/exercises")
@RequiredArgsConstructor
public class ExercisesController {

    private final ExerciseAnalysisService analysisService;

    /**
     * [쪼개기 1] 기준 데이터 미리 준비 (등록 단계)
     * 앱이 아닌 관리자나 등록자가 유튜브 URL을 보내면, 파이썬이 좌표를 따서 DB에 채워둡니다.
     */
    @PostMapping("/{exerciseId}/extract")
    public ResponseEntity<String> extractReference(@PathVariable Long exerciseId, @RequestBody String youtubeUrl) {
        analysisService.extractReferencePoses(exerciseId, youtubeUrl);
        return ResponseEntity.ok("기준 좌표 추출 요청을 파이썬 서버로 전달했습니다.");
    }

    /**
     * [쪼개기 2] 실제 운동 세션 시작 (실행 단계)
     * 사용자가 '운동 시작'을 누르면 호출됩니다.
     * 내부적으로는 App->Spring(접수) 후 Spring->FastAPI(비동기 전송)가 일어납니다.
     */
    @PostMapping("/sessions")
    public ResponseEntity<String> startAnalysis(@RequestBody VideoRequestDto dto,
                                                @AuthenticationPrincipal CustomUserDetails userDetails){
        Long memberId = userDetails.getMember().getId();

        // 이 안에서 '접수'와 '비동기 전송 호출'이 일어납니다.
        Long sessionId = analysisService.startAnalysis(dto, memberId);

        // 앱에게는 "접수됐으니 분석 시작할게!"라고 바로 대답해줍니다.
        return ResponseEntity.accepted().body("분석 세션이 시작되었습니다. 작업 ID: " + sessionId);
    }
}