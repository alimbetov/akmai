package kz.alimbetov.akmai.runtimeconfig;

import jakarta.validation.Valid;
import java.util.List;
import kz.alimbetov.akmai.security.ApiKeyPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/app-parameters")
public class AppParameterController {

    private final AppParameterService service;

    public AppParameterController(AppParameterService service) {
        this.service = service;
    }

    @GetMapping
    public List<AppParameterResponse> list() {
        return service.list().stream()
                .map(AppParameterResponse::from)
                .toList();
    }

    @GetMapping("/{key}")
    public AppParameterResponse get(
            @PathVariable String key
    ) {
        return AppParameterResponse.from(
                service.get(AppParameterKey.parse(key))
        );
    }

    @PutMapping("/{key}")
    public AppParameterResponse update(
            @PathVariable String key,
            @Valid @RequestBody AppParameterUpdateRequest request
    ) {
        ResolvedAppParameter updated =
                service.updateBoolean(
                        AppParameterKey.parse(key),
                        request.value(),
                        request.expectedVersion(),
                        actor()
                );
        return AppParameterResponse.from(updated);
    }

    private String actor() {
        Authentication authentication =
                SecurityContextHolder.getContext()
                        .getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal()
                instanceof ApiKeyPrincipal principal) {
            return principal.name();
        }
        return "local-admin";
    }
}
