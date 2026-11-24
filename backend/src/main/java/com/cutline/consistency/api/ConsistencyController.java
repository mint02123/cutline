package com.cutline.consistency.api;

import com.cutline.consistency.ConsistencyChecker;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 부하 테스트·장애 주입 스크립트가 끝에 호출하는 내부용 API. 외부에 열지 않는다
 * (배포 단계에서 내부 포트·네트워크 정책으로 막고, 필요 없으면 cutline.internal.enabled=false).
 */
@RestController
@ConditionalOnProperty(name = "cutline.internal.enabled", havingValue = "true", matchIfMissing = true)
public class ConsistencyController {

    private final ConsistencyChecker checker;

    public ConsistencyController(ConsistencyChecker checker) {
        this.checker = checker;
    }

    @GetMapping("/internal/consistency")
    Result check() {
        List<ConsistencyChecker.Violation> violations = checker.check();
        return new Result(violations.isEmpty(), ConsistencyChecker.RULES.size(), violations);
    }

    public record Result(boolean consistent, int rulesChecked, List<ConsistencyChecker.Violation> violations) {
    }
}
