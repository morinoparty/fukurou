package party.morino.fukurou.junit.platform

import org.junit.jupiter.api.ClassDescriptor
import org.junit.jupiter.api.ClassOrderer
import org.junit.jupiter.api.ClassOrdererContext

/**
 * 同じ fukurou の拡張（= 同じサーバー）を使うテストクラスを続けて実行する ClassOrderer（§5.2）。
 *
 * 拡張のクラス名を並べた列、次にクラス名で並べる。サーバーを使うクラスが途切れないので、メモリ予算による
 * 退避と作り直しが減る。junit.jupiter.testclass.order.default に FQCN を指定して使う。
 */
public class FukurouClassOrderer : ClassOrderer {
    /** クラスを並べ替える（リストをその場で並べ替えるのが ClassOrderer の約束）。 */
    override fun orderClasses(context: ClassOrdererContext) {
        @Suppress("UNCHECKED_CAST")
        val descriptors = context.classDescriptors as MutableList<ClassDescriptor>
        descriptors.sortWith(compareBy<ClassDescriptor>({ sortKey(it.testClass) }, { it.testClass.name }))
    }

    /** 並べ替えの鍵。 */
    internal companion object {
        /** 拡張のクラス名を整列して連結したもの（fukurou を使わないクラスは空文字列で先頭）。 */
        fun sortKey(testClass: Class<*>): String =
            FukurouExtensions.find(testClass).map { it.name }.sorted().joinToString(",")
    }
}
