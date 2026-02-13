package com.ureca.snac.loadtest;

import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.event.MemberJoinEvent;
import com.ureca.snac.member.exception.EmailDuplicateException;
import com.ureca.snac.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static com.ureca.snac.member.Activated.NORMAL;
import static com.ureca.snac.member.Role.USER;

/**
 * 부하 테스트 전용 회원가입 서비스
 * 이메일/SMS 인증을 건너뛰고 회원 생성 + MemberJoinEvent 발행
 */
@Slf4j
@Service
@Profile("loadtest")
@RequiredArgsConstructor
public class LoadTestService {

    private final MemberRepository memberRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void joinForLoadTest(String email, String password, String name, String nickname, String phone) {

        if (memberRepository.existsByEmail(email)) {
            throw new EmailDuplicateException();
        }

        Member member = Member.builder()
                .email(email)
                .password(passwordEncoder.encode(password))
                .name(name)
                .nickname(nickname)
                .phone(phone)
                .birthDate(LocalDate.of(1990, 1, 1))
                .role(USER)
                .ratingScore(100)
                .activated(NORMAL)
                .build();

        memberRepository.save(member);
        log.info("[LoadTest 회원가입] 완료. email: {}", email);

        eventPublisher.publishEvent(new MemberJoinEvent(member.getId()));
    }
}
