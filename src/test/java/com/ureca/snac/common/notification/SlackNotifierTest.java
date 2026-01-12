package com.ureca.snac.common.notification;

import com.ureca.snac.common.notification.dto.SlackAttachment;
import com.ureca.snac.common.notification.dto.SlackField;
import com.ureca.snac.common.notification.dto.SlackMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClientException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * - HTTP 요청 URL 및 Method 확인
 * - DTO -> JSON 직렬화 검증 (요청 바디 확인)
 * - 500 에러 시 예외 전파 여부 확인
 */
@RestClientTest(SlackNotifier.class) // RestClient 관련 빈만 로드
@TestPropertySource(properties =
        "slack.webhook.url=https://hooks.slack.com/services/test")
class SlackNotifierTest {

    @Autowired
    private SlackNotifier slackNotifier;

    @Autowired
    private MockRestServiceServer mockServer;

    @Test
    @DisplayName("성공 : Slack 메시지 전송 완료 및 JSON 검증")
    void sendAsync_Success() {
        // given
        SlackMessage message = createTestMessage();

        // Expectation 요청 기대
        mockServer.expect(requestTo(containsString("hooks.slack.com"))) // URL 확인
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.text").value("테스트 메시지"))
                .andExpect(jsonPath("$.attachments[0].fields[0].title").value("큐"))
                .andExpect(jsonPath("$.attachments[0].fields[0].value").value("test.queue"))
                .andRespond(withSuccess());

        // when
        slackNotifier.sendAsync(message);

        // then
        mockServer.verify();
    }

    @Test
    @DisplayName("예외 : 서버 오류(500) 발생 시 예외가 전파되어야 한다")
    void sendAsync_RestClientException() {
        // given
        SlackMessage message = createTestMessage();

        mockServer.expect(requestTo(containsString("hooks.slack.com")))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withServerError()); // 500 에러

        // when , then
        // 예외 발생 성공
        assertThatThrownBy(() -> slackNotifier.sendAsync(message))
                .isInstanceOf(RestClientException.class);

        mockServer.verify();
    }

    private SlackMessage createTestMessage() {
        List<SlackField> fields = List.of(
                SlackField.of("큐", "test.queue"),
                SlackField.of("개수", "5건")
        );

        return SlackMessage.of("테스트 메시지", SlackAttachment.danger(fields));
    }
}