package com.softropic.payam.disbursement.api;

import com.softropic.payam.platform.admin.service.PaymentMetricsService;
import com.softropic.payam.orange.contract.OrangeWebhookPayload;
import com.softropic.payam.orange.service.OrangeMoneyPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrangeDisbursementCallbackControllerTest {

    private OrangeMoneyPort port;
    private PaymentMetricsService metrics;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        port = mock(OrangeMoneyPort.class);
        metrics = mock(PaymentMetricsService.class);
        OrangeDisbursementCallbackController sut =
            new OrangeDisbursementCallbackController(port, metrics);
        mockMvc = MockMvcBuilders.standaloneSetup(sut).build();
    }

    private static final String VALID_BODY =
        "{\"payToken\":\"pt-001\",\"status\":\"SUCCESS\",\"notif_token\":\"tok-abc\"," +
        "\"txnid\":\"TXN-001\",\"msisdn\":\"237691111111\",\"amount\":\"100\"," +
        "\"createtime\":\"2026-04-25T10:00:00\"}";

    @Test
    void handleDisbursementCallback_returns200_andDelegatesToPortWithNotifTokenHeader() throws Exception {
        mockMvc.perform(post("/v1/callbacks/orange/disbursement")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Notif-Token", "tok-abc")
                .content(VALID_BODY))
            .andExpect(status().isOk());

        ArgumentCaptor<OrangeWebhookPayload> payloadCaptor =
            ArgumentCaptor.forClass(OrangeWebhookPayload.class);
        verify(port).processDisbursementCallback(payloadCaptor.capture(), eq("tok-abc"));
        assertThat(payloadCaptor.getValue().getPayToken()).isEqualTo("pt-001");
        assertThat(payloadCaptor.getValue().getStatus()).isEqualTo("SUCCESS");
        verify(metrics).recordCallbackReceived();
        verify(metrics, never()).recordCallbackFailed();
    }

    @Test
    void handleDisbursementCallback_returns200_evenWhenPortThrows() throws Exception {
        doThrow(new RuntimeException("simulated port failure"))
            .when(port).processDisbursementCallback(any(), anyString());

        mockMvc.perform(post("/v1/callbacks/orange/disbursement")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Notif-Token", "tok-abc")
                .content(VALID_BODY))
            .andExpect(status().isOk());

        verify(metrics).recordCallbackReceived();
        verify(metrics).recordCallbackFailed();
    }

    @Test
    void handleDisbursementCallback_returns200_whenNotifTokenHeaderAbsent() throws Exception {
        mockMvc.perform(post("/v1/callbacks/orange/disbursement")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isOk());

        verify(port).processDisbursementCallback(any(), isNull());
    }

    @Test
    void controllerNotAnnotatedTransactional() throws Exception {
        assertThat(OrangeDisbursementCallbackController.class.isAnnotationPresent(Transactional.class))
            .isFalse();
        Method handler = OrangeDisbursementCallbackController.class.getDeclaredMethod(
            "handleDisbursementCallback", OrangeWebhookPayload.class, String.class);
        assertThat(handler.isAnnotationPresent(Transactional.class)).isFalse();
    }
}
