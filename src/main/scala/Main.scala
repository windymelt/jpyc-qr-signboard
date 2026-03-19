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
    ethRpc: String = "https://cloudflare-eth.com",
)

// ---------------------------------------------------------------------------
// ERC-681 URI builder (server-side, for console display only)
// ---------------------------------------------------------------------------

object Erc681:
  /** ERC-681 URI for ERC-20 transfer.
    *
    * Format:
    *   ethereum:<token>[@<chainId>]/transfer?address=<to>[&uint256=<amount>]
    *
    * uint256 uses EIP-681 exponential notation: <amount>e<decimals>
    * e.g. 1000 JPYC (18 decimals) => uint256=1000e18
    *
    * Note: if recipientAddress is an ENS name, it is embedded as-is here
    * (console only). The actual QR code URI is built client-side after
    * ENS resolution.
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
  def render(cfg: Config): String =
    val amountLine = cfg.amount match
      case None         => "（金額は送信者が指定）"
      case Some(amount) => s"${amount.setScale(0, BigDecimal.RoundingMode.DOWN)} XXX"

    val chainLine = cfg.chainId match
      case None     => "（チェーン未指定）"
      case Some(id) => s"$id"

    // JS values injected from Scala
    val jsTo          = jsonString(cfg.recipientAddress)
    val jsToken       = jsonString(cfg.tokenAddress)
    val jsChainId     = cfg.chainId.fold("null")(_.toString)
    val jsAmountHuman     = cfg.amount match
      case None        => "null"
      case Some(human) => jsonString(human.underlying.stripTrailingZeros.toPlainString)
    val jsDecimalsFallback = cfg.decimals.toString
    val jsRpc              = jsonString(cfg.ethRpc)

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
      position: relative;
    }

    #qr canvas, #qr img { display: block; }

    .qr-loading {
      width: 280px;
      height: 280px;
      display: flex;
      align-items: center;
      justify-content: center;
      color: #aaa;
      font-size: 0.85rem;
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

    .address-with-icon {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      margin-top: 0.15rem;
    }

    .icons-group {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      flex-shrink: 0;
    }

    .icon-slot {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 0.2rem;
    }

    .icon-label {
      font-size: 0.6rem;
      color: #5a5a7a;
      letter-spacing: 0.06em;
      text-transform: uppercase;
    }

    #jazzicon-to, #maskicon-to {
      width: 48px;
      height: 48px;
    }
    #jazzicon-to > div, #maskicon-to {
      border-radius: 50%;
      overflow: hidden;
    }

    .address-texts { display: flex; flex-direction: column; gap: 0.2rem; }

    .ens-name {
      font-size: 1rem;
      color: #c8c8e0;
      font-weight: 600;
    }

    .resolved-addr {
      font-size: 0.78rem;
      word-break: break-all;
      color: #7878a0;
      font-family: 'Courier New', Courier, monospace;
    }

    .resolved-addr.loading { color: #4a4a6a; font-style: italic; }

    .verify-note {
      margin-top: 0.35rem;
      font-size: 0.68rem;
      color: #5a5a7a;
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

    .main-content {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 2rem;
      width: 100%;
    }

    .qr-section {
      display: flex;
      flex-direction: column;
      align-items: center;
    }

    @media (min-width: 760px) and (max-height: 768px) {
      .main-content {
        flex-direction: row;
        align-items: flex-start;
        justify-content: center;
        gap: 3rem;
      }
    }

    .scan-instruction {
      margin-bottom: 1rem;
      text-align: center;
      display: flex;
      flex-direction: column;
      gap: 0.2rem;
    }

    .scan-instruction .primary {
      font-size: clamp(0.95rem, 2.5vw, 1.15rem);
      font-weight: 600;
      color: #ffffff;
    }

    .scan-instruction .secondary {
      font-size: clamp(0.72rem, 1.8vw, 0.85rem);
      color: #6a6a8a;
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

  <div class="main-content">
    <div class="qr-section">
      <div class="scan-instruction">
        <span class="primary">ウォレットアプリのスキャン機能で読み取ってください</span>
        <span class="secondary">カメラアプリではなく MetaMask・Trust Wallet 等のアプリ内スキャナーをご利用ください</span>
      </div>

      <div class="qr-wrapper">
        <div id="qr"><div class="qr-loading">読み込み中...</div></div>
      </div>
    </div>

    <div class="info-card">
    <div class="info-row">
      <span class="info-label">支払い金額</span>
      <span class="info-value highlight" id="amount-display">${escape(amountLine)}</span>
    </div>
    <div class="info-row">
      <span class="info-label">送金先アドレス</span>
      <div class="address-with-icon">
        <div class="icons-group">
          <div class="icon-slot">
            <div id="jazzicon-to"></div>
            <span class="icon-label">Jazzicon</span>
          </div>
          <div class="icon-slot">
            <div id="maskicon-to"></div>
            <span class="icon-label">Maskicon</span>
          </div>
        </div>
        <div class="address-texts">
          ${addressDisplay(cfg.recipientAddress)}
        </div>
      </div>
      <span class="verify-note">ウォレットアプリのアイコンと照合してください</span>
    </div>
    <div class="info-row">
      <span class="info-label">トークンコントラクト</span>
      <div class="address-texts">
        <span class="ens-name" id="token-name">読み込み中...</span>
        <span class="resolved-addr">${escape(cfg.tokenAddress)}</span>
      </div>
    </div>
    <div class="info-row">
      <span class="info-label">ネットワーク</span>
      <div class="address-texts">
        <span class="ens-name" id="chain-name">読み込み中...</span>
        ${cfg.chainId.fold("")(id => s"""<span class="resolved-addr">Chain ID: $id</span>""")}
      </div>
    </div>
    <div class="uri-row" id="uri-display">読み込み中...</div>
  </div>
  </div>

  <footer>ERC-681 / Scan with a Web3 wallet app &nbsp;|&nbsp; <a href="https://github.com/windymelt/jpyc-qr-signboard" target="_blank" rel="noopener noreferrer" style="color:inherit;text-decoration:underline;text-underline-offset:3px;">github.com/windymelt/jpyc-qr-signboard</a></footer>

  <script type="module">
    import jazzicon from 'https://esm.sh/@metamask/jazzicon';
    import { ethers } from 'https://esm.sh/ethers@6';

    // ---------------------------------------------------------------------------
    // Maskicon — ported from @metamask/design-system (MIT)
    // https://github.com/MetaMask/metamask-design-system
    // ---------------------------------------------------------------------------
    const _neutralPairs = [
      ['#FF5C16','#FCFCFC'],['#FF5C16','#131416'],['#D075FF','#FCFCFC'],['#D075FF','#131416'],
      ['#BAF24A','#FCFCFC'],['#BAF24A','#131416'],['#89B0FF','#FCFCFC'],['#89B0FF','#131416'],
      ['#FCFCFC','#FF5C16'],['#131416','#FF5C16'],['#FCFCFC','#D075FF'],['#131416','#D075FF'],
      ['#FCFCFC','#BAF24A'],['#131416','#BAF24A'],['#FCFCFC','#89B0FF'],['#131416','#89B0FF'],
    ];
    const _tonalPairs = [
      ['#FFA680','#FF5C16'],['#661800','#FF5C16'],['#EAC2FF','#D075FF'],['#3D065F','#D075FF'],
      ['#E5FFC3','#BAF24A'],['#013330','#BAF24A'],['#CCE7FF','#89B0FF'],['#190066','#89B0FF'],
      ['#FF5C16','#FFA680'],['#FF5C16','#661800'],['#D075FF','#EAC2FF'],['#D075FF','#3D065F'],
      ['#BAF24A','#E5FFC3'],['#BAF24A','#013330'],['#89B0FF','#CCE7FF'],['#89B0FF','#190066'],
      ['#661800','#FFA680'],['#FFA680','#661800'],['#3D065F','#EAC2FF'],['#EAC2FF','#3D065F'],
      ['#013330','#E5FFC3'],['#E5FFC3','#013330'],['#190066','#CCE7FF'],['#CCE7FF','#190066'],
    ];
    const _complementaryPairs = [
      ['#EAC2FF','#013330'],['#013330','#EAC2FF'],['#CCE7FF','#661800'],['#661800','#CCE7FF'],
      ['#E5FFC3','#3D065F'],['#3D065F','#E5FFC3'],['#FFA680','#190066'],['#190066','#FFA680'],
      ['#CCE7FF','#013330'],['#013330','#CCE7FF'],
    ];
    const _colorPairs = _neutralPairs.concat(_tonalPairs).concat(_complementaryPairs);

    function _sdbmHash(str) {
      let hash = 0;
      for (let i = 0; i < str.length; i++) {
        hash = str.charCodeAt(i) + (hash << 6) + (hash << 16) - hash;
      }
      return hash;
    }

    function _seedToString(seed) {
      let hex = (typeof seed === 'number')
        ? seed.toString(16)
        : seed.map(b => b.toString(16).padStart(2, '0')).join('');
      return hex.length < 6 ? hex.padEnd(6, '0') : hex;
    }

    function createMaskiconSVG(address, size = 100) {
      const seed = /^0x/i.test(address)
        ? parseInt(address.slice(2, 10), 16)
        : Array.from(new TextEncoder().encode(address.normalize('NFKC').toLowerCase()));
      const hashVal = _sdbmHash(_seedToString(seed));
      const [bgColor, fgColor] = _colorPairs[Math.abs(hashVal) % _colorPairs.length];

      const grid = 2, margin = size * 0.25;
      const cellSize = (size - 2 * margin) / grid;
      let pathData = '';
      const filled = Array.from({ length: grid }, () => Array(grid).fill(false));
      const sx = Math.floor(grid / 2), sy = Math.floor(grid / 2);
      const stack = [[sx, sy]];
      filled[sx][sy] = true;

      while (stack.length > 0) {
        const [x, y] = stack.pop();
        const ch = Math.abs(hashVal >> (x * 3 + y * 5)) & 15;
        const neighbors = [];
        for (const [dx, dy] of [[0,1],[1,0],[0,-1],[-1,0]]) {
          const nx = x + dx, ny = y + dy;
          if (nx >= 0 && nx < grid && ny >= 0 && ny < grid && !filled[nx][ny]) {
            neighbors.push([nx, ny]);
          }
        }
        while (neighbors.length > 0) {
          const idx = Math.abs(ch + neighbors.length) % neighbors.length;
          const [nx, ny] = neighbors.splice(idx, 1)[0];
          stack.push([nx, ny]);
          filled[nx][ny] = true;
        }
        const rot = (ch % 4) * 90;
        const cx = margin + x * cellSize, cy = margin + y * cellSize;
        if (ch % 5 === 0) {
          pathData += `M$${cx},$${cy} h$${cellSize} v$${cellSize} h-$${cellSize}z `;
        } else if (rot === 0) {
          pathData += `M$${cx},$${cy} h$${cellSize} v$${cellSize}z `;
        } else if (rot === 90) {
          pathData += `M$${cx + cellSize},$${cy} v$${cellSize} h-$${cellSize}z `;
        } else if (rot === 180) {
          pathData += `M$${cx + cellSize},$${cy + cellSize} h-$${cellSize} v-$${cellSize}z `;
        } else {
          pathData += `M$${cx},$${cy + cellSize} v-$${cellSize} h$${cellSize}z `;
        }
      }
      return `<svg width="$${size}" height="$${size}" viewBox="0 0 $${size} $${size}" xmlns="http://www.w3.org/2000/svg"><rect width="$${size}" height="$${size}" fill="$${bgColor}"/><path d="$${pathData}" fill="$${fgColor}"/></svg>`;
    }
    // ---------------------------------------------------------------------------

    const TO_PARAM         = $jsTo;
    const TOKEN_ADDRESS    = $jsToken;
    const CHAIN_ID         = $jsChainId;
    const AMOUNT_HUMAN     = $jsAmountHuman;
    const DECIMALS_FALLBACK = $jsDecimalsFallback;
    const ETH_RPC          = $jsRpc;

    function isEns(addr) {
      return !/^0x[0-9a-fA-F]{40}$$/i.test(addr);
    }

    function buildUri(resolvedTo, amountStr) {
      const chainPart = CHAIN_ID != null ? `@$${CHAIN_ID}` : '';
      let uri = `ethereum:$${TOKEN_ADDRESS}$${chainPart}/transfer?address=$${resolvedTo}`;
      if (amountStr != null) uri += `&uint256=$${amountStr}`;
      return uri;
    }

    // ETH_RPC is tried first; if it fails, the built-in fallbacks are tried.
    const FALLBACK_RPCS = [
      ETH_RPC,
      'https://rpc.ankr.com/eth',
      'https://eth.llamarpc.com',
      'https://ethereum.publicnode.com',
      'https://1rpc.io/eth',
    ].filter((v, i, a) => a.indexOf(v) === i); // deduplicate

    async function resolveEns(name) {
      let lastErr;
      for (const rpc of FALLBACK_RPCS) {
        try {
          const provider = new ethers.JsonRpcProvider(rpc);
          const addr = await provider.resolveName(name);
          if (addr) return addr;
          // resolveName returns null when name is not registered
          throw new Error(`ENS名 "$${name}" はまだ登録されていません`);
        } catch (e) {
          lastErr = e;
          // if the name is clearly unregistered, stop trying other RPCs
          if (e.message.includes('登録されていません')) throw e;
        }
      }
      throw new Error(
        `ENS解決に失敗しました: $${lastErr?.message ?? '不明なエラー'}\n` +
        `別のRPCを試す場合は --eth-rpc オプションで指定してください。`
      );
    }

    // Fetch chain metadata (name, rpc URLs) from ethereum-lists via jsDelivr CDN.
    async function fetchChainData(chainId) {
      if (chainId == null) return null;
      try {
        const res = await fetch(
          `https://cdn.jsdelivr.net/gh/ethereum-lists/chains/_data/chains/eip155-$${chainId}.json`
        );
        return res.ok ? await res.json() : null;
      } catch {
        return null;
      }
    }

    // Return HTTP(S) RPC URLs that don't require an API key.
    function publicRpcs(chainData) {
      return (chainData?.rpc ?? []).filter(
        r => typeof r === 'string' && r.startsWith('http') && !r.includes('$${')
      );
    }

    // Call name(), symbol() and decimals() on the ERC-20 token contract.
    async function fetchTokenInfo(rpcs, tokenAddress) {
      const abi = [
        'function name() view returns (string)',
        'function symbol() view returns (string)',
        'function decimals() view returns (uint8)',
      ];
      for (const rpc of rpcs) {
        try {
          const provider = new ethers.JsonRpcProvider(rpc);
          const contract = new ethers.Contract(tokenAddress, abi, provider);
          const [name, symbol, decimals] = await Promise.all([
            contract.name(),
            contract.symbol(),
            contract.decimals(),
          ]);
          return { name: String(name), symbol: String(symbol), decimals: Number(decimals) };
        } catch {
          continue;
        }
      }
      return null;
    }

    async function init() {
      let resolvedTo = TO_PARAM;

      // Start chain data fetch and ENS resolution in parallel.
      const chainDataPromise = fetchChainData(CHAIN_ID);

      if (isEns(TO_PARAM)) {
        const el = document.getElementById('resolved-addr');
        if (el) { el.textContent = 'ENS解決中...'; el.className = 'resolved-addr loading'; }
        resolvedTo = await resolveEns(TO_PARAM);
        if (el) { el.textContent = resolvedTo; el.className = 'resolved-addr'; }
      }

      // Wait for chain data so we can fetch token info (decimals).
      const chainData = await chainDataPromise;
      document.getElementById('chain-name').textContent =
        chainData?.name ?? (CHAIN_ID != null ? `Chain ID: $${CHAIN_ID}` : '（チェーン未指定）');

      // Fetch token symbol + decimals from the chain's public RPCs.
      const rpcs = publicRpcs(chainData);
      const tokenInfo = rpcs.length > 0
        ? await fetchTokenInfo(rpcs, TOKEN_ADDRESS)
        : null;

      // Prefer on-chain decimals; fall back to the CLI --decimals value.
      const decimals  = tokenInfo?.decimals ?? DECIMALS_FALLBACK;
      const amountStr = AMOUNT_HUMAN != null ? `$${AMOUNT_HUMAN}e$${decimals}` : null;

      if (tokenInfo != null) {
        if (AMOUNT_HUMAN != null) {
          document.getElementById('amount-display').textContent =
            `$${AMOUNT_HUMAN} $${tokenInfo.symbol}`;
        }
        document.getElementById('token-name').textContent = tokenInfo.name;
      }

      // Build final URI (with correct decimals) and render QR code.
      const uri = buildUri(resolvedTo, amountStr);
      document.getElementById('uri-display').textContent = uri;

      await new Promise((resolve, reject) => {
        QRCode.toCanvas(
          uri,
          {
            width: Math.min(Math.max(window.innerWidth * 0.55, 200), 320),
            margin: 0,
            color: { dark: '#000000', light: '#ffffff' },
            errorCorrectionLevel: 'M',
          },
          (err, canvas) => {
            if (err) { reject(err); return; }
            const container = document.getElementById('qr');
            container.innerHTML = '';
            container.appendChild(canvas);
            resolve();
          }
        );
      });

      // Jazzicon — uses the resolved Ethereum address as seed.
      const clean = resolvedTo.replace(/^0x/i, '');
      const seed  = parseInt(clean.slice(0, 8), 16);
      document.getElementById('jazzicon-to').appendChild(jazzicon(48, seed));

      // Maskicon — MetaMask design system identicon from the same resolved address.
      document.getElementById('maskicon-to').innerHTML = createMaskiconSVG(resolvedTo, 48);
    }

    init().catch(err => {
      console.error(err);
      document.getElementById('jazzicon-to').textContent = 'error';
      document.getElementById('uri-display').textContent = 'エラー: ' + err.message;
    });
  </script>
</body>
</html>"""

  /** アドレス表示HTML。ENS名の場合は名前と解決済みアドレス欄を分けて表示する。 */
  private def addressDisplay(addr: String): String =
    if isEnsAddress(addr) then
      s"""<span class="ens-name">${escape(addr)}</span>
          <span class="resolved-addr loading" id="resolved-addr">ENS解決中...</span>"""
    else
      s"""<span class="info-value">${escape(addr)}</span>"""

  private def isEnsAddress(addr: String): Boolean =
    !addr.matches("0x[0-9a-fA-F]{40}")

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
        .valueName("<address_or_ens>")
        .action((x, c) => c.copy(recipientAddress = x))
        .text("Recipient address or ENS name, e.g. 0x... or windymelt.eth (required)"),
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
      opt[String]("eth-rpc")
        .optional()
        .valueName("<url>")
        .action((x, c) => c.copy(ethRpc = x))
        .text("Ethereum JSON-RPC URL for ENS resolution (default: https://cloudflare-eth.com)"),
    )

  OParser.parse(parser, args.toSeq, Config()) match
    case None => sys.exit(1)
    case Some(cfg) =>
      val uri  = Erc681.transferUri(cfg)
      val html = HtmlTemplate.render(cfg)

      println(s"ERC-681 URI (console only; ENS and on-chain decimals not resolved): $uri")

      cfg.outputFile match
        case Some(path) =>
          Files.writeString(Paths.get(path), html)
          println(s"HTML written to: $path")
        case None =>
          Server.start(cfg.port, html)
