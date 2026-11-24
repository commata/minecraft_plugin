package kr.commata.bank.core;

import java.util.UUID;

/** accounts 한 줄. name 은 마지막으로 본 닉네임 (없을 수 있음). */
public record Account(UUID uuid, String name, long balance) {
}
