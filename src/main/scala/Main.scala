import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.file.{Files, Paths}
import scopt.OParser

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------

case class Config(
    tokenAddress: String = "",
    recipientAddress: String = "",
    amount: Option[BigDecimal] = None,
    decimals: Int = 18,
    chainId: Option[Int] = None,
    port: Int = 8080,
    outputFile: Option[String] = None,
    title: String = "Token Payment",
)

// ---------------------------------------------------------------------------
// ERC-681 URI builder
// ---------------------------------------------------------------------------

object Erc681:
  /** ERC-681 URI for ERC-20 transfer.
    *
    * Format:
    *   ethereum:<token>[@<chainId>]/transfer?address=<to>[&uint256=<amount>]
    *
    * uint256 uses EIP-681 exponential notation: <amount>e<decimals>
    * e.g. 1000 JPYC (18 decimals) => uint256=1000e18
    */
  def transferUri(cfg: Config): String =
    val chainPart = cfg.chainId.fold("")(id => s"@$id")
    val base      = s"ethereum:${cfg.tokenAddress}${chainPart}/transfer?address=${cfg.recipientAddress}"
    cfg.amount match
      case None => base
      case Some(human) =>
        val amountStr = human.underlying.stripTrailingZeros.toPlainString
        s"$base&uint256=${amountStr}e${cfg.decimals}"

// ---------------------------------------------------------------------------
// HTML template
// ---------------------------------------------------------------------------

object HtmlTemplate:
  def render(cfg: Config, uri: String): String =
    val amountLine = cfg.amount match
      case None         => "（金額は送信者が指定）"
      case Some(amount) => s"${amount.setScale(0, BigDecimal.RoundingMode.DOWN)} トークン"

    val chainLine = cfg.chainId match
      case None     => "（チェーン未指定）"
      case Some(id) => s"Chain ID: $id"

    s"""<!DOCTYPE html>
<html lang="ja">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${escape(cfg.title)}</title>
  <script src="https://cdn.jsdelivr.net/npm/qrcode/build/qrcode.min.js"></script>
  <style>
    *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

    body {
      min-height: 100dvh;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      background: #0a0a0f;
      color: #e8e8f0;
      font-family: 'Helvetica Neue', Arial, 'Hiragino Kaku Gothic ProN', 'Meiryo', sans-serif;
      padding: 2rem;
    }

    h1 {
      font-size: clamp(1.5rem, 4vw, 2.8rem);
      font-weight: 700;
      letter-spacing: 0.04em;
      margin-bottom: 2rem;
      text-align: center;
      color: #ffffff;
    }

    .qr-wrapper {
      background: #ffffff;
      border-radius: 1.2rem;
      padding: 1.5rem;
      box-shadow: 0 0 60px rgba(120, 80, 255, 0.35);
      margin-bottom: 2rem;
    }

    #qr canvas, #qr img { display: block; }

    .address-with-icon {
      display: flex;
      align-items: center;
      gap: 0.75rem;
    }

    #jazzicon-to > div {
      border-radius: 50%;
      overflow: hidden;
      flex-shrink: 0;
    }

    .verify-note {
      margin-top: 0.3rem;
      font-size: 0.68rem;
      color: #5a5a7a;
    }

    .info-card {
      background: #14141e;
      border: 1px solid #2a2a3a;
      border-radius: 0.8rem;
      padding: 1.4rem 2rem;
      width: 100%;
      max-width: 540px;
    }

    .info-row {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
      padding: 0.6rem 0;
      border-bottom: 1px solid #1e1e2e;
    }
    .info-row:last-child { border-bottom: none; }

    .info-label {
      font-size: 0.72rem;
      text-transform: uppercase;
      letter-spacing: 0.12em;
      color: #7878a0;
    }

    .info-value {
      font-size: 0.95rem;
      word-break: break-all;
      color: #c8c8e0;
      font-family: 'Courier New', Courier, monospace;
    }

    .info-value.highlight {
      color: #a0c8ff;
      font-size: 1.15rem;
      font-family: inherit;
      font-weight: 600;
    }

    .uri-row {
      margin-top: 1.2rem;
      padding: 0.8rem;
      background: #0e0e18;
      border-radius: 0.5rem;
      font-size: 0.7rem;
      word-break: break-all;
      color: #4a4a6a;
      font-family: 'Courier New', Courier, monospace;
    }

    footer {
      margin-top: 2.5rem;
      font-size: 0.7rem;
      color: #3a3a5a;
      text-align: center;
    }
  </style>
</head>
<body>
  <h1>${escape(cfg.title)}</h1>

  <div class="qr-wrapper">
    <canvas id="qr"></canvas>
  </div>

  <div class="info-card">
    <div class="info-row">
      <span class="info-label">支払い金額</span>
      <span class="info-value highlight">${escape(amountLine)}</span>
    </div>
    <div class="info-row">
      <span class="info-label">送金先アドレス</span>
      <div class="address-with-icon">
        <div id="jazzicon-to"></div>
        <span class="info-value">${escape(cfg.recipientAddress)}</span>
      </div>
      <span class="verify-note">ウォレットアプリのアイコンと照合してください</span>
    </div>
    <div class="info-row">
      <span class="info-label">トークンコントラクト</span>
      <span class="info-value">${escape(cfg.tokenAddress)}</span>
    </div>
    <div class="info-row">
      <span class="info-label">ネットワーク</span>
      <span class="info-value">${escape(chainLine)}</span>
    </div>
    <div class="uri-row">${escape(uri)}</div>
  </div>

  <footer>ERC-681 / Scan with a Web3 wallet app</footer>

  <script>
    QRCode.toCanvas(
      document.getElementById('qr'),
      ${jsonString(uri)},
      {
        width: Math.min(Math.max(window.innerWidth * 0.55, 200), 420),
        margin: 0,
        color: { dark: '#000000', light: '#ffffff' },
        errorCorrectionLevel: 'M',
      },
      function(err) { if (err) console.error(err); }
    );
  </script>

  <script type="module">
    import jazzicon from 'https://esm.sh/@metamask/jazzicon';
    const addr = ${jsonString(cfg.recipientAddress)};
    const clean = addr.replace(/^0x/i, '');
    const seed  = parseInt(clean.slice(0, 8), 16);
    const el    = jazzicon(48, seed);
    document.getElementById('jazzicon-to').appendChild(el);
  </script>
</body>
</html>"""

  private def escape(s: String): String =
    s.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;")

  private def jsonString(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// ---------------------------------------------------------------------------
// HTTP server
// ---------------------------------------------------------------------------

object Server:
  def start(port: Int, html: String): Unit =
    val server = HttpServer.create(new InetSocketAddress(port), 0)
    server.createContext(
      "/",
      (exchange: HttpExchange) => {
        val body = html.getBytes("UTF-8")
        exchange.getResponseHeaders.set("Content-Type", "text/html; charset=UTF-8")
        exchange.sendResponseHeaders(200, body.length)
        val out = exchange.getResponseBody
        out.write(body)
        out.close()
      },
    )
    server.setExecutor(null)
    server.start()
    println(s"Serving at http://localhost:$port/")
    println("Ctrl+C to stop.")

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

@main def run(args: String*): Unit =
  val builder = OParser.builder[Config]
  val parser =
    import builder.*
    OParser.sequence(
      programName("jpyc-qr-signboard"),
      head("jpyc-qr-signboard", "0.1.0"),
      opt[String]("token")
        .required()
        .valueName("<contract_address>")
        .action((x, c) => c.copy(tokenAddress = x))
        .text("ERC-20 token contract address (required)"),
      opt[String]("to")
        .required()
        .valueName("<recipient_address>")
        .action((x, c) => c.copy(recipientAddress = x))
        .text("Recipient wallet address (required)"),
      opt[String]("amount")
        .optional()
        .valueName("<amount>")
        .action((x, c) => c.copy(amount = Some(BigDecimal(x))))
        .text("Amount in human-readable units, e.g. 1000 (optional)"),
      opt[Int]("decimals")
        .optional()
        .valueName("<n>")
        .action((x, c) => c.copy(decimals = x))
        .text("Token decimals (default: 18)"),
      opt[Int]("chain-id")
        .optional()
        .valueName("<id>")
        .action((x, c) => c.copy(chainId = Some(x)))
        .text("Chain ID, e.g. 1=Ethereum, 137=Polygon (optional)"),
      opt[Int]("port")
        .optional()
        .valueName("<port>")
        .action((x, c) => c.copy(port = x))
        .text("HTTP port (default: 8080)"),
      opt[String]("output")
        .optional()
        .valueName("<file>")
        .action((x, c) => c.copy(outputFile = Some(x)))
        .text("Output HTML to file instead of starting HTTP server"),
      opt[String]("title")
        .optional()
        .valueName("<text>")
        .action((x, c) => c.copy(title = x))
        .text("Page title shown on signage (default: Token Payment)"),
    )

  OParser.parse(parser, args.toSeq, Config()) match
    case None => sys.exit(1)
    case Some(cfg) =>
      val uri  = Erc681.transferUri(cfg)
      val html = HtmlTemplate.render(cfg, uri)

      println(s"ERC-681 URI: $uri")

      cfg.outputFile match
        case Some(path) =>
          Files.writeString(Paths.get(path), html)
          println(s"HTML written to: $path")
        case None =>
          Server.start(cfg.port, html)
