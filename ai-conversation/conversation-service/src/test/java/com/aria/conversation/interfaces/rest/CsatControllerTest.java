package com.aria.conversation.interfaces.rest;

import com.aria.conversation.application.service.CsatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CsatControllerTest {

    @Mock CsatService service;
    @InjectMocks CsatController controller;

    @Test
    void rate_delegatesToService() {
        CsatController.RateRequest req = new CsatController.RateRequest();
        req.setScore((short) 5);
        req.setComment("很满意");
        controller.rate(42L, req);
        verify(service).rate(42L, (short) 5, "很满意");
    }

    @Test
    void skip_delegatesToService() {
        controller.skip(7L);
        verify(service).skip(7L);
    }
}
