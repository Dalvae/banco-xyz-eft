package cl.duoc.bank.mobile.controller;

import cl.duoc.bank.mobile.dto.MobileDtos;
import cl.duoc.bank.mobile.service.MobileHomeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile")
public class MobileController {
    private final MobileHomeService service;

    public MobileController(MobileHomeService service) {
        this.service = service;
    }

    @GetMapping("/me/inicio")
    @PreAuthorize("hasAuthority('SCOPE_mobile:read')")
    public MobileDtos.Home home(@AuthenticationPrincipal Jwt jwt) {
        return service.home(jwt.getTokenValue());
    }
}
