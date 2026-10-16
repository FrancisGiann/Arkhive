package mihon.readingapp.books

import org.json.JSONObject

/** App-owned reading styles, applied through Readium's supported per-resource API.
 * No publication text or executable content is interpolated; fixed-layout books are excluded.
 * Sizes use em so the saved font and line-height controls continue to scale the page.
 */
internal object InkBookTypography {
    // Match Readium advanced-selector specificity without changing publication IDs or content.
    private val css = """
        html[data-arkhive-reading][data-arkhive-reading][data-arkhive-reading] body { padding-block-start: 47px !important; }
        html[data-arkhive-reading][data-arkhive-reading][data-arkhive-reading] body p { max-inline-size: min(100%, 13.57143em); font-size: 1em !important; text-indent: 0 !important; text-align: start; margin-block: .95em !important; }
        html[data-arkhive-reading][data-arkhive-reading][data-arkhive-reading] body h1, html[data-arkhive-reading][data-arkhive-reading][data-arkhive-reading] body h2, html[data-arkhive-reading][data-arkhive-reading][data-arkhive-reading] body h3, html[data-arkhive-reading][data-arkhive-reading][data-arkhive-reading] body h4, html[data-arkhive-reading][data-arkhive-reading][data-arkhive-reading] body h5, html[data-arkhive-reading][data-arkhive-reading][data-arkhive-reading] body h6 {
            font-family: 'Arkhive Serif', 'Noto Serif', serif !important;
            font-weight: 400 !important; letter-spacing: 0 !important;
            text-align: start !important; line-height: 1.22 !important;
            margin-block: .65em !important;
        }
        html[data-arkhive-reading][data-arkhive-reading][data-arkhive-reading] body h1 { max-inline-size: min(100%, 9.375em); font-size: 1.52381em !important; margin-block-end: .65em !important; }
        html[data-arkhive-reading][data-arkhive-reading][data-arkhive-reading] body h2 { font-size: 1.3em !important; }
        html[data-arkhive-reading][data-arkhive-reading][data-arkhive-reading] body h2:has(+ h1) {
            font-family: 'Arkhive Sans', sans-serif !important;
            font-size: .57143em !important; font-weight: 700 !important;
            letter-spacing: .15em !important; text-transform: uppercase;
            opacity: .7; margin-block-start: 0 !important; margin-block-end: 1.15em !important;
        }
        html[data-arkhive-reading][data-arkhive-reading][data-arkhive-reading] body h2 + h1 { margin-block-start: 0 !important; }
        html[data-arkhive-reading][data-arkhive-reading][data-arkhive-reading] body h1::after { content: ''; display: block; width: 44px; border-bottom: 2px solid #8793A3; margin-top: .58em; }
    """.trimIndent()

    val script: String = """
        (() => {
            document.documentElement.setAttribute('data-arkhive-reading', '');
            if (document.getElementById('arkhive-reading-typography')) return 'present';
            const style = document.createElement('style');
            style.id = 'arkhive-reading-typography';
            style.textContent = ${JSONObject.quote(css)};
            document.head.appendChild(style);
            return 'applied';
        })();
    """.trimIndent()
}
