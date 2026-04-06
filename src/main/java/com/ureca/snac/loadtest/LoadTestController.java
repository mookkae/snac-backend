package com.ureca.snac.loadtest;

import com.ureca.snac.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static com.ureca.snac.common.BaseCode.TRADE_DATA_SENT_SUCCESS;
import static com.ureca.snac.common.BaseCode.USER_SIGNUP_SUCCESS;

@RestController
@Profile("loadtest")
@RequestMapping("/api/loadtest")
@RequiredArgsConstructor
public class LoadTestController {

    private final LoadTestService loadTestService;
    private final LoadTestTradeService loadTestTradeService;

    @PostMapping("/join")
    public ResponseEntity<ApiResponse<Void>> joinForLoadTest(@RequestBody LoadTestJoinRequest request) {
        loadTestService.joinForLoadTest(
                request.email(),
                request.password(),
                request.name(),
                request.nickname(),
                request.phone()
        );
        return ResponseEntity.ok(ApiResponse.ok(USER_SIGNUP_SUCCESS));
    }

    /**
     * E2E 테스트 전용: seller 검증·S3·SMS 전부 스킵하고 상태만 DATA_SENT로 변경
     */
    @PostMapping("/trades/{tradeId}/mark-data-sent")
    public ResponseEntity<ApiResponse<Void>> markDataSent(@PathVariable Long tradeId) {
        loadTestTradeService.markDataSent(tradeId);
        return ResponseEntity.ok(ApiResponse.ok(TRADE_DATA_SENT_SUCCESS));
    }

    public record LoadTestJoinRequest(
            String email,
            String password,
            String name,
            String nickname,
            String phone
    ) {
    }
}
