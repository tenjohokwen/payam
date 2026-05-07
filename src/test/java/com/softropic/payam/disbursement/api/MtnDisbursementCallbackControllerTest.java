package com.softropic.payam.disbursement.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.payam.platform.admin.service.PaymentMetricsService;
import com.softropic.payam.mtn.contract.MtnCallbackPayload;
import com.softropic.payam.mtn.service.MtnMoMoPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MtnDisbursementCallbackControllerTest {

    private MtnMoMoPort port;
    private PaymentMetricsService metrics;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        port = mock(MtnMoMoPort.class);
        metrics = mock(PaymentMetricsService.class);
        MtnDisbursementCallbackController sut = new MtnDisbursementCallbackController(port, metrics);
        mockMvc = MockMvcBuilders.standaloneSetup(sut).build();
    }

    @Test
    void handleDisbursementCallback_returns200_andDelegatesToPortWithPathVariable() throws Exception {
        MtnCallbackPayload payload = new MtnCallbackPayload();
        payload.setExternalId("dsb-001");
        payload.setStatus("SUCCESSFUL");
        payload.setFinancialTransactionId("ftx-001");
        String body = objectMapper.writeValueAsString(payload);

        mockMvc.perform(put("/v1/callbacks/mtn/disbursement/ref-uuid-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());

        ArgumentCaptor<MtnCallbackPayload> payloadCaptor = ArgumentCaptor.forClass(MtnCallbackPayload.class);
        verify(port).processDisbursementCallback(payloadCaptor.capture(), eq("ref-uuid-123"));
        assertThat(payloadCaptor.getValue().getExternalId()).isEqualTo("dsb-001");
        assertThat(payloadCaptor.getValue().getStatus()).isEqualTo("SUCCESSFUL");
        verify(metrics).recordCallbackReceived();
        verify(metrics, never()).recordCallbackFailed();
    }

    @Test
    void handleDisbursementCallback_returns200_evenWhenPortThrows() throws Exception {
        doThrow(new RuntimeException("simulated port failure"))
            .when(port).processDisbursementCallback(org.mockito.ArgumentMatchers.any(),
                                                     org.mockito.ArgumentMatchers.anyString());

        mockMvc.perform(put("/v1/callbacks/mtn/disbursement/ref-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalId\":\"dsb-2\",\"status\":\"FAILED\"}"))
            .andExpect(status().isOk());

        verify(metrics).recordCallbackReceived();
        verify(metrics).recordCallbackFailed();
    }

    @Test
    void controllerNotAnnotatedTransactional() throws Exception {
        assertThat(MtnDisbursementCallbackController.class.isAnnotationPresent(Transactional.class))
            .as("Controller class must NOT be @Transactional — Pitfall 1 in 52-RESEARCH")
            .isFalse();
        Method handler = MtnDisbursementCallbackController.class.getDeclaredMethod(
            "handleDisbursementCallback", String.class, MtnCallbackPayload.class,
            jakarta.servlet.http.HttpServletRequest.class);
        assertThat(handler.isAnnotationPresent(Transactional.class))
            .as("Handler method must NOT be @Transactional")
            .isFalse();
    }
}
