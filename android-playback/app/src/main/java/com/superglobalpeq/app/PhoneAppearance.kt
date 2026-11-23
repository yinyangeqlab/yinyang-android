package com.superglobalpeq.app

internal data class PhoneAppearance(
    val background: Int, val panel: Int, val line: Int, val ink: Int, val muted: Int,
    val accent: Int, val warning: Int, val active: Int
) {
    companion object {
        val names = arrayOf("Blue", "AMOLED black", "Light", "Green", "YinYangEQ theme")
        fun at(index: Int): PhoneAppearance = when (index) {
            1 -> PhoneAppearance(0xff000000.toInt(), 0xff0c0c0c.toInt(), 0xff474747.toInt(),
                0xfffafafa.toInt(), 0xffbdbdbd.toInt(), 0xffa9d6ff.toInt(), 0xffffb59d.toInt(), 0xff192c3c.toInt())
            2 -> PhoneAppearance(0xfff8fafc.toInt(), 0xffffffff.toInt(), 0xffa2aaba.toInt(),
                0xff172139.toInt(), 0xff4b586e.toInt(), 0xff254fa3.toInt(), 0xff973b32.toInt(), 0xffdfebff.toInt())
            3 -> PhoneAppearance(0xff151b15.toInt(), 0xff23342b.toInt(), 0xff687657.toInt(),
                0xfff8f3df.toInt(), 0xffc2cfad.toInt(), 0xffefd189.toInt(), 0xfff6b298.toInt(), 0xff374b37.toInt())
            4 -> PhoneAppearance(0xff000000.toInt(), 0xff102329.toInt(), 0xff405859.toInt(),
                0xffedf3f7.toInt(), 0xff97aabd.toInt(), 0xff58d8c9.toInt(), 0xfff4795a.toInt(), 0xff183438.toInt())
            else -> PhoneAppearance(0xff101827.toInt(), 0xff182f35.toInt(), 0xff536e7a.toInt(),
                0xffeff5fa.toInt(), 0xffb6c8d4.toInt(), 0xff9fdfc5.toInt(), 0xffffb292.toInt(), 0xff243d50.toInt())
        }
    }
}
