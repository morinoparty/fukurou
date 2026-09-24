"""fukurou 全体で使う例外の基底クラス。"""


class FukurouError(RuntimeError):
    """fukurou が原因を説明できる失敗。メッセージはそのまま利用者に表示する。"""


class InvalidInputError(FukurouError):
    """利用者の入力（引数・シナリオ・バージョン指定など）が不正な場合に送出する。終了コードは 2 になる。"""
