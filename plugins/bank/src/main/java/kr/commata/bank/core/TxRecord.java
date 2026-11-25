package kr.commata.bank.core;

import java.util.UUID;

/**
 * transactions 한 줄 + 그 거래가 일어난 기동의 상태(boots.status).
 *
 * @param from           돈이 빠져나간 계좌 (없으면 null)
 * @param to             돈이 들어간 계좌 (없으면 null)
 * @param fromBalanceAfter from 계좌의 거래 후 잔액
 * @param toBalanceAfter   to 계좌의 거래 후 잔액
 * @param ref            환불 거래가 가리키는 원래 거래번호 (없으면 0)
 * @param bootStatus     running / clean / unclean
 */
public record TxRecord(long id, long timeMs, TxType type, UUID from, UUID to, long amount,
                       Long fromBalanceAfter, Long toBalanceAfter, String source, String bootId,
                       String context, long ref, String bootStatus) {
}
