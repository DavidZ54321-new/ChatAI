package com.zcw.chatai.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SvgAnimationTest {

    @Test
    fun smilAnimateIsAnimated() {
        assertEquals(
            SvgAnimation.Kind.SMIL_OR_CSS,
            SvgAnimation.kind(
                "<svg><circle r=\"10\">" +
                    "<animate attributeName=\"cx\" from=\"0\" to=\"100\" dur=\"2s\"/>" +
                    "</circle></svg>",
            ),
        )
    }

    @Test
    fun animateTransformIsAnimated() {
        assertEquals(
            SvgAnimation.Kind.SMIL_OR_CSS,
            SvgAnimation.kind(
                "<svg><g><animateTransform attributeName=\"transform\" " +
                    "type=\"rotate\" from=\"0\" to=\"360\" dur=\"3s\"/></g></svg>",
            ),
        )
    }

    @Test
    fun cssKeyframesIsAnimated() {
        assertEquals(
            SvgAnimation.Kind.SMIL_OR_CSS,
            SvgAnimation.kind(
                "<svg><style>@keyframes spin{to{transform:rotate(360deg)}}" +
                    ".a{animation:spin 2s linear infinite}</style><rect class=\"a\"/></svg>",
            ),
        )
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals(
            SvgAnimation.Kind.SMIL_OR_CSS,
            SvgAnimation.kind("<SVG><ANIMATE attributeName=\"x\"/></SVG>"),
        )
    }

    @Test
    fun scriptOnlyIsNotGifCapable() {
        assertEquals(
            SvgAnimation.Kind.SCRIPT_ONLY,
            SvgAnimation.kind("<svg><script>setInterval(()=>{},100)</script><circle r=\"5\"/></svg>"),
        )
    }

    @Test
    fun staticSvgHasNoAnimation() {
        assertEquals(
            SvgAnimation.Kind.NONE,
            SvgAnimation.kind("<svg><rect width=\"10\" height=\"10\"/></svg>"),
        )
    }

    @Test
    fun cssAnimationNoneIsNotAnimated() {
        assertEquals(
            SvgAnimation.Kind.NONE,
            SvgAnimation.kind("<svg><style>.a{animation: none}</style><rect class=\"a\"/></svg>"),
        )
    }

    @Test
    fun cssAnimationWithNameIsAnimated() {
        assertEquals(
            SvgAnimation.Kind.SMIL_OR_CSS,
            SvgAnimation.kind("<svg><style>.a{animation: spin 1s}</style><rect class=\"a\"/></svg>"),
        )
    }

    @Test
    fun emptyCodeHasNoAnimation() {
        assertEquals(SvgAnimation.Kind.NONE, SvgAnimation.kind(""))
    }

    @Test
    fun smilTakesPrecedenceOverScript() {
        assertEquals(
            SvgAnimation.Kind.SMIL_OR_CSS,
            SvgAnimation.kind(
                "<svg><script>init()</script>" +
                    "<animate attributeName=\"opacity\" values=\"1;0\" dur=\"1s\"/></svg>",
            ),
        )
    }
}
