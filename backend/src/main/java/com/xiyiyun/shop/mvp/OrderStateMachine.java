package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;

public final class OrderStateMachine {
    private OrderStateMachine() {
    }

    public static boolean canStartMockPayment(OrderStatus status) {
        return status == OrderStatus.CREATED || status == OrderStatus.UNPAID;
    }

    public static boolean canExpirePayment(OrderStatus status) {
        return status == OrderStatus.CREATED || status == OrderStatus.UNPAID;
    }

    public static void assertCanAcceptPaymentCallback(OrderItem order) {
        OrderStatus status = order.status();
        if (status == OrderStatus.CREATED || status == OrderStatus.UNPAID || status == OrderStatus.PAYING) {
            return;
        }
        throw new IllegalStateException("order cannot accept payment callback in current status");
    }

    public static void assertCanCancel(OrderItem order) {
        if (order.status() == OrderStatus.CREATED || order.status() == OrderStatus.UNPAID) {
            return;
        }
        throw new IllegalStateException("only unpaid orders can be cancelled");
    }

    public static void assertCanCompleteManual(OrderItem order) {
        if (order.status() == OrderStatus.WAITING_MANUAL) {
            return;
        }
        throw new IllegalStateException("only waiting manual orders can be completed");
    }

    public static void assertCanRetryProcurement(OrderItem order) {
        if (order.goodsType() != GoodsType.DIRECT) {
            throw new IllegalStateException("only direct orders can be retried");
        }
        if (order.status() == OrderStatus.FAILED || order.status() == OrderStatus.PROCURING) {
            return;
        }
        throw new IllegalStateException("only failed or procuring orders can be retried");
    }

    public static void assertCanRefund(OrderItem order) {
        if (order.status() == OrderStatus.REFUNDED) {
            return;
        }
        if (order.status() == OrderStatus.CANCELLED || order.status() == OrderStatus.CREATED || order.status() == OrderStatus.UNPAID) {
            throw new IllegalStateException("order cannot be refunded");
        }
    }

    public static void assertCanManualMarkSuccess(OrderItem order) {
        if (isTerminal(order.status())) {
            throw new IllegalStateException("terminal orders cannot be marked success");
        }
    }

    public static void assertCanManualMarkFailed(OrderItem order) {
        if (isTerminal(order.status())) {
            throw new IllegalStateException("terminal orders cannot be marked failed");
        }
    }

    private static boolean isTerminal(OrderStatus status) {
        return status == OrderStatus.DELIVERED ||
            status == OrderStatus.REFUNDED ||
            status == OrderStatus.CANCELLED ||
            status == OrderStatus.CLOSED;
    }
}
