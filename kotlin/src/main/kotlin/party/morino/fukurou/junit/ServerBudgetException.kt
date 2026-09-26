package party.morino.fukurou.junit

import party.morino.fukurou.error.FukurouException

/**
 * 同時に必要なサーバーとクライアントのメモリの見積もりが予算（fukurou.memoryBudgetMb）を超えた。
 *
 * 何も起動する前に投げるので、ランナーが OOM で落ちる代わりに、何が何 MB を使うかと予算の上げ方がメッセージに残る。
 *
 * @param message 見積もりの内訳と -Pfukurou.memoryBudgetMb の案内
 */
public class ServerBudgetException(message: String) : FukurouException(message)
