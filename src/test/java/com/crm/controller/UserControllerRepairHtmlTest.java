package com.crm.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The static {@code UserController#repairHtml} helper fixes two recurring breakages in
 * pasted user.memo HTML — missing {@code </style>} and orphaned {@code </script>}.
 * These have caused entire memo pages to render as raw CSS or invisible, so we lock the
 * fixup behaviour in tests.
 */
class UserControllerRepairHtmlTest {

    @Test
    void nullInput_returnsNull() {
        assertThat(UserController.repairHtml(null)).isNull();
    }

    @Test
    void wellFormedHtml_isReturnedUnchanged() {
        String well = "<html><head><style>p{color:red}</style></head><body><p>hi</p></body></html>";
        assertThat(UserController.repairHtml(well)).isEqualTo(well);
    }

    @Test
    void missingStyleClosing_strayGtBecomesStyleClose() {
        // Original breakage seen in the wild: someone copy-pasted from a designer tool and the
        // "</style>" turned into a stray ">" on its own line right before "</head>".
        String broken = "<html><head><style>p{color:red}\n>\n</head><body><p>hi</p></body></html>";
        String fixed = UserController.repairHtml(broken);
        assertThat(fixed)
                .contains("</style>")
                .doesNotContain("\n>\n</head>");
    }

    @Test
    void missingStyleClosing_noStrayGt_injectsBeforeHead() {
        // If there's no stray ">" to reuse, inject "</style>" right before "</head>" so the
        // browser stops eating the rest of the document as CSS.
        String broken = "<html><head><style>p{color:red}\n</head><body><p>hi</p></body></html>";
        String fixed = UserController.repairHtml(broken);
        assertThat(fixed).contains("</style>");
        // The first "</style>" must appear before the body.
        int styleClose = fixed.indexOf("</style>");
        int bodyStart = fixed.indexOf("<body");
        assertThat(styleClose).isLessThan(bodyStart);
    }

    @Test
    void orphanScriptClosing_strayGtBecomesScriptOpen() {
        // The matching breakage: "<script>" was lost and a stray ">" sits before the JS body.
        String broken = "<html><body><footer>x</footer>\n>\nalert('hi');\n</script></body></html>";
        String fixed = UserController.repairHtml(broken);
        assertThat(fixed)
                .contains("<script>")
                .doesNotContain("\n>\nalert");
    }

    @Test
    void orphanScriptClosing_noStrayGt_appendsAfterFooter() {
        String broken = "<html><body><footer>x</footer>alert('hi');</script></body></html>";
        String fixed = UserController.repairHtml(broken);
        // After <footer> repair injects "<script>"
        int scriptOpen = fixed.indexOf("<script>");
        int scriptClose = fixed.indexOf("</script>");
        assertThat(scriptOpen).isPositive().isLessThan(scriptClose);
    }

    @Test
    void orphanScriptClosing_noFooterAndNoStrayGt_dropsScriptClose() {
        // Fallback: when we can't infer where <script> should go, just strip the orphan </script>
        // so the rest of the page isn't all-script.
        String broken = "<html><body>some content</script></body></html>";
        String fixed = UserController.repairHtml(broken);
        assertThat(fixed).doesNotContain("</script>");
        assertThat(fixed).contains("some content");
    }
}
