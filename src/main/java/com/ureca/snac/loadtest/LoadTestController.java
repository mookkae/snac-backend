package com.ureca.snac.loadtest;

import com.ureca.snac.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.ureca.snac.common.BaseCode.USER_SIGNUP_SUCCESS;

@RestController
@Profile("loadtest")
@RequestMapping("/api/loadtest")
@RequiredArgsConstructor
public class LoadTestController {

    private final LoadTestService loadTestService;

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

    public record LoadTestJoinRequest(
            String email,
            String password,
            String name,
            String nickname,
            String phone
    ) {
    }
}
