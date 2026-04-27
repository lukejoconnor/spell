const sharp = require('sharp');
const fs = require('fs');
const path = require('path');
const pptxgen = require('pptxgenjs');
const html2pptx = require('/Users/lukeoconnor/.claude/skills/pptx/scripts/html2pptx.js');

const WS = __dirname;
const IMG = path.join(WS, 'img');
const SLIDES = path.join(WS, 'slides');
fs.mkdirSync(IMG, { recursive: true });
fs.mkdirSync(SLIDES, { recursive: true });

// ─── SVG Helpers ────────────────────────────────────────

const DEFS = `<defs>
  <marker id="ah" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto-start-reverse">
    <path d="M0,0 L10,3.5 L0,7 L2.5,3.5 Z" fill="#4A5568"/>
  </marker>
  <marker id="ah-teal" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto-start-reverse">
    <path d="M0,0 L10,3.5 L0,7 L2.5,3.5 Z" fill="#277884"/>
  </marker>
  <marker id="ah-coral" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto-start-reverse">
    <path d="M0,0 L10,3.5 L0,7 L2.5,3.5 Z" fill="#E07A5F"/>
  </marker>
  <marker id="ah-gold" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto-start-reverse">
    <path d="M0,0 L10,3.5 L0,7 L2.5,3.5 Z" fill="#D4A843"/>
  </marker>
</defs>`;

function svg(w, h, body) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" font-family="Arial,Helvetica,sans-serif">${DEFS}${body}</svg>`;
}

// ─── Diagram 1: Agentic Machine ─────────────────────────

function agenticMachineSVG() {
  return svg(700, 340, `
    <rect x="40" y="55" width="90" height="55" rx="10" fill="#277884"/>
    <text x="85" y="88" text-anchor="middle" fill="#fff" font-size="20" font-weight="bold">s</text>

    <rect x="255" y="55" width="120" height="55" rx="10" fill="#E07A5F"/>
    <text x="315" y="88" text-anchor="middle" fill="#fff" font-size="18" font-weight="bold">p(s)</text>

    <rect x="495" y="55" width="90" height="55" rx="10" fill="#D4A843"/>
    <text x="540" y="88" text-anchor="middle" fill="#fff" font-size="20" font-weight="bold">c</text>

    <rect x="275" y="245" width="100" height="55" rx="10" fill="#277884"/>
    <text x="325" y="278" text-anchor="middle" fill="#fff" font-size="20" font-weight="bold">s\u2032</text>

    <!-- s -> p(s) -->
    <line x1="132" y1="82" x2="252" y2="82" stroke="#4A5568" stroke-width="2.5" marker-end="url(#ah)"/>
    <text x="192" y="72" text-anchor="middle" fill="#4A5568" font-size="17" font-style="italic">p</text>

    <!-- p(s) -> c -->
    <line x1="377" y1="82" x2="492" y2="82" stroke="#4A5568" stroke-width="2.5" marker-end="url(#ah)"/>
    <text x="435" y="69" text-anchor="middle" fill="#4A5568" font-size="17" font-style="italic">M</text>

    <!-- s -> s' -->
    <path d="M85,112 C85,195 275,215 310,243" fill="none" stroke="#4A5568" stroke-width="2.5" marker-end="url(#ah)"/>

    <!-- c -> s' -->
    <path d="M540,112 C540,195 375,215 345,243" fill="none" stroke="#4A5568" stroke-width="2.5" marker-end="url(#ah)"/>

    <!-- h label -->
    <text x="325" y="200" text-anchor="middle" fill="#4A5568" font-size="17" font-style="italic">h(s, c)</text>
  `);
}

// ─── Diagram 2: Embedding ───────────────────────────────

function embeddingSVG() {
  return svg(720, 450, `
    <!-- S' ellipse -->
    <ellipse cx="155" cy="170" rx="115" ry="145" fill="#E8F4F2" stroke="#277884" stroke-width="2.5"/>
    <text x="155" y="22" text-anchor="middle" fill="#277884" font-size="20" font-weight="bold">S\u2032</text>

    <!-- S ellipse -->
    <ellipse cx="555" cy="170" rx="115" ry="145" fill="#FFF3ED" stroke="#E07A5F" stroke-width="2.5"/>
    <text x="555" y="22" text-anchor="middle" fill="#E07A5F" font-size="20" font-weight="bold">S</text>

    <!-- P rectangle -->
    <rect x="290" y="380" width="130" height="50" rx="8" fill="#FEF3C7" stroke="#D4A843" stroke-width="2"/>
    <text x="355" y="410" text-anchor="middle" fill="#B89035" font-size="18" font-weight="bold">P</text>

    <!-- Points in S' -->
    <circle cx="135" cy="100" r="7" fill="#277884"/>
    <text x="93" y="97" text-anchor="end" fill="#1B2838" font-size="15" font-weight="bold">s\u2032\u2081</text>

    <circle cx="150" cy="240" r="7" fill="#277884"/>
    <text x="108" y="237" text-anchor="end" fill="#1B2838" font-size="15" font-weight="bold">s\u2032\u2082</text>

    <!-- Points in S -->
    <circle cx="535" cy="100" r="7" fill="#E07A5F"/>
    <text x="535" y="82" text-anchor="middle" fill="#1B2838" font-size="14">e(s\u2032\u2081)</text>

    <circle cx="550" cy="240" r="7" fill="#E07A5F"/>
    <text x="550" y="262" text-anchor="middle" fill="#1B2838" font-size="14">e(s\u2032\u2082)</text>

    <!-- e arrows -->
    <line x1="145" y1="100" x2="525" y2="100" stroke="#4A5568" stroke-width="2" marker-end="url(#ah)"/>
    <text x="340" y="90" text-anchor="middle" fill="#4A5568" font-size="17" font-style="italic" font-weight="bold">e</text>

    <line x1="160" y1="240" x2="540" y2="240" stroke="#4A5568" stroke-width="2" marker-end="url(#ah)"/>
    <text x="340" y="232" text-anchor="middle" fill="#4A5568" font-size="17" font-style="italic" font-weight="bold">e</text>

    <!-- T' arrow (inside S') -->
    <path d="M130,112 C95,170 120,220 145,232" fill="none" stroke="#277884" stroke-width="2.5" marker-end="url(#ah-teal)"/>
    <text x="85" y="175" text-anchor="middle" fill="#277884" font-size="16" font-weight="bold">T\u2032</text>

    <!-- T arrow (inside S) -->
    <path d="M530,112 C495,170 520,220 545,232" fill="none" stroke="#E07A5F" stroke-width="2.5" marker-end="url(#ah-coral)"/>
    <text x="485" y="175" text-anchor="middle" fill="#E07A5F" font-size="16" font-weight="bold">T</text>

    <!-- p' arrow (dashed) -->
    <line x1="140" y1="108" x2="305" y2="378" stroke="#D4A843" stroke-width="1.8" stroke-dasharray="6,4" marker-end="url(#ah-gold)"/>
    <text x="190" y="268" text-anchor="middle" fill="#B89035" font-size="15" font-style="italic">p\u2032</text>

    <!-- p arrow (dashed) -->
    <line x1="540" y1="108" x2="405" y2="378" stroke="#D4A843" stroke-width="1.8" stroke-dasharray="6,4" marker-end="url(#ah-gold)"/>
    <text x="505" y="268" text-anchor="middle" fill="#B89035" font-size="15" font-style="italic">p</text>

    <!-- same prompt label -->
    <text x="355" y="370" text-anchor="middle" fill="#B89035" font-size="13" font-style="italic">same prompt</text>
  `);
}

// ─── Diagram 3: Completion-Generation ───────────────────

function completionGenSVG() {
  return svg(660, 400, `
    <!-- Outer state space X -->
    <ellipse cx="340" cy="205" rx="295" ry="175" fill="#F8F9FA" stroke="#4A5568" stroke-width="2.5"/>
    <text x="600" y="52" text-anchor="end" fill="#4A5568" font-size="20" font-weight="bold">X</text>

    <!-- Inner embedded space im(e) -->
    <ellipse cx="410" cy="220" rx="170" ry="130" fill="#E8F4F2" stroke="#277884" stroke-width="2" stroke-dasharray="8,4"/>
    <text x="535" y="112" text-anchor="start" fill="#277884" font-size="15" font-style="italic">im(e)</text>

    <!-- x_seed -->
    <circle cx="100" cy="175" r="9" fill="#E07A5F" stroke="#C4614A" stroke-width="2"/>
    <text x="100" y="155" text-anchor="middle" fill="#C4614A" font-size="16" font-weight="bold">x</text>
    <text x="112" y="161" fill="#C4614A" font-size="10">seed</text>

    <!-- e(x1) -->
    <circle cx="330" cy="155" r="7" fill="#277884"/>
    <text x="330" y="138" text-anchor="middle" fill="#1B2838" font-size="14">e(x\u2081)</text>

    <!-- e(x2) -->
    <circle cx="480" cy="195" r="7" fill="#277884"/>
    <text x="495" y="183" text-anchor="start" fill="#1B2838" font-size="14">e(x\u2082)</text>

    <!-- e(x3) -->
    <circle cx="395" cy="300" r="7" fill="#277884"/>
    <text x="395" y="325" text-anchor="middle" fill="#1B2838" font-size="14">e(x\u2083)</text>

    <!-- c1 arrow -->
    <line x1="110" y1="172" x2="322" y2="157" stroke="#4A5568" stroke-width="2" marker-end="url(#ah)"/>
    <text x="212" y="153" text-anchor="middle" fill="#4A5568" font-size="15" font-style="italic">c\u2081</text>

    <!-- c2 arrow -->
    <line x1="110" y1="178" x2="472" y2="194" stroke="#4A5568" stroke-width="2" marker-end="url(#ah)"/>
    <text x="295" y="200" text-anchor="middle" fill="#4A5568" font-size="15" font-style="italic">c\u2082</text>

    <!-- c3 arrow -->
    <line x1="108" y1="183" x2="388" y2="295" stroke="#4A5568" stroke-width="2" marker-end="url(#ah)"/>
    <text x="220" y="258" text-anchor="middle" fill="#4A5568" font-size="15" font-style="italic">c\u2083</text>
  `);
}

// ─── Rasterize diagrams ─────────────────────────────────

async function rasterize() {
  const items = [
    ['agentic-machine.png', agenticMachineSVG()],
    ['embedding.png', embeddingSVG()],
    ['completion-gen.png', completionGenSVG()],
  ];
  for (const [name, svgStr] of items) {
    await sharp(Buffer.from(svgStr), { density: 200 })
      .png().toFile(path.join(IMG, name));
  }
}

// ─── HTML Templates ─────────────────────────────────────

const CSS_BASE = `
html { background: #ffffff; }
body { width: 720pt; height: 405pt; margin: 0; padding: 0; font-family: Arial, sans-serif; display: flex; flex-direction: column; }
`;

function contentSlide(title, body) {
  return `<!DOCTYPE html><html><head><style>
${CSS_BASE}
body { background: #ffffff; }
.hdr { background: #1B2838; padding: 9pt 30pt; min-height: 38pt; display: flex; align-items: center; flex-shrink: 0; }
.bar { height: 3pt; background: #277884; flex-shrink: 0; }
.body { flex: 1; padding: 14pt 30pt 12pt 30pt; overflow: hidden; }
.defbox { background: #E8F4F2; border-left: 4pt solid #277884; padding: 9pt 13pt; margin: 6pt 0; }
.thmbox { background: #FFF8F0; border-left: 4pt solid #D4A843; padding: 9pt 13pt; margin: 6pt 0; }
.codebox { background: #EDEDF3; border-left: 4pt solid #4A5568; padding: 8pt 13pt; margin: 6pt 0; }
.cols { display: flex; gap: 18pt; height: 100%; }
.col { flex: 1; }
</style></head><body>
<div class="hdr"><h2 style="color: #ffffff; font-size: 21pt; margin: 0;">${title}</h2></div>
<div class="bar"></div>
<div class="body">${body}</div>
</body></html>`;
}

// ─── Slide 1: Title ─────────────────────────────────────

function slide1() {
  return `<!DOCTYPE html><html><head><style>
${CSS_BASE}
body { background: #1B2838; justify-content: center; align-items: center; }
</style></head><body>
<div style="text-align: center; margin-top: -20pt;">
  <h1 style="color: #ffffff; font-size: 30pt; margin: 0 0 8pt 0;">Self-Programmed Execution</h1>
  <p style="color: #7EB8C9; font-size: 19pt; margin: 0 0 28pt 0;">Theoretical Foundations</p>
  <p style="color: #6B8299; font-size: 14pt; margin: 0 0 4pt 0;">Luke O'Connor</p>
  <p style="color: #5A7088; font-size: 12pt; margin: 0;">Group Meeting \u00b7 April 24, 2026</p>
</div>
</body></html>`;
}

// ─── Slide 2: Agentic Machine ───────────────────────────

function slide2() {
  const imgPath = path.join(IMG, 'agentic-machine.png');
  return contentSlide('Agentic Machines', `
<div class="cols">
  <div class="col">
    <div class="defbox">
      <p style="font-size: 13pt; color: #2C2C2C; margin: 0 0 6pt 0;"><b>Definition.</b> An <b>agentic machine</b> <i>X</i> = (<i>S</i>, <i>p</i>, <i>h</i>):</p>
      <ul style="font-size: 12pt; color: #2C2C2C; margin: 3pt 0; padding-left: 16pt;">
        <li><b>State space</b> <i>S</i></li>
        <li><b>Prompt function</b> <i>p</i> : <i>S</i> \u2192 <i>P</i></li>
        <li><b>Harness function</b> <i>h</i> : <i>S</i> \u00d7 <i>C</i> \u2192 <i>S</i> \u222a {halt, \u2191}</li>
      </ul>
    </div>
    <p style="font-size: 11.5pt; color: #444; margin: 8pt 0 0 0;">Exactly <b>one model call</b> per state transition. The machine constructs a prompt, the model returns a completion, and the harness computes the next state.</p>
  </div>
  <div class="col" style="display: flex; align-items: center; justify-content: center;">
    <img src="${imgPath}" style="width: 310pt;">
  </div>
</div>`);
}

// ─── Slide 3: Embedding ─────────────────────────────────

function slide3() {
  const imgPath = path.join(IMG, 'embedding.png');
  return contentSlide('Embeddings', `
<div class="defbox" style="margin-bottom: 6pt;">
  <p style="font-size: 12pt; color: #2C2C2C; margin: 0;"><b>Definition.</b> An <b>embedding</b> <i>e</i> : <i>S\u2032</i> \u2192 <i>S</i> is an injection with: &nbsp; <b>prompt preservation</b> <i>p</i>(<i>e</i>(<i>s\u2032</i>)) = <i>p\u2032</i>(<i>s\u2032</i>) &nbsp;and&nbsp; <b>transition commutation</b> <i>h</i>(<i>e</i>(<i>s\u2032</i>), <i>c</i>) = <i>e</i>(<i>h\u2032</i>(<i>s\u2032</i>, <i>c</i>))</p>
</div>
<div style="display: flex; justify-content: center;">
  <img src="${imgPath}" style="width: 390pt;">
</div>`);
}

// ─── Slide 4: Theorem 3.3 ──────────────────────────────

function slide4() {
  return contentSlide('Invisible Embeddings', `
<div class="thmbox">
  <p style="font-size: 15pt; color: #2C2C2C; margin: 0 0 8pt 0;"><b>Theorem C.3.3.</b> Embeddings are invisible to the language model.</p>
  <p style="font-size: 13pt; color: #2C2C2C; margin: 0;">If <i>e</i> embeds <i>X\u2032</i> into <i>X</i>, then for every state <i>s\u2032</i> and every sequence of future completions, the model sees exactly the same prompts whether it interacts with <i>X\u2032</i> from <i>s\u2032</i> or with <i>X</i> from <i>e</i>(<i>s\u2032</i>).</p>
</div>
<p style="font-size: 13pt; color: #2C2C2C; margin: 14pt 0 0 0;">The model cannot distinguish an embedded copy from the original. The only thing visible to the model is the prompt, and an embedding preserves both prompts and the dynamics of every future completion sequence.</p>
<p style="font-size: 12pt; color: #666; margin: 12pt 0 0 0;">Formally: (<i>X\u2032</i>, <i>s\u2032</i>) ~ (<i>X</i>, <i>e</i>(<i>s\u2032</i>)) &nbsp; where ~ is <b>LM-indistinguishability</b> \u2014 identical prompt traces under all completion sequences.</p>`);
}

// ─── Slide 5: Completion-Generation ─────────────────────

function slide5() {
  const imgPath = path.join(IMG, 'completion-gen.png');
  return contentSlide('Completion-Generation', `
<div class="cols">
  <div class="col">
    <div class="defbox">
      <p style="font-size: 13pt; color: #2C2C2C; margin: 0 0 6pt 0;"><b>Definition C.3.6.</b></p>
      <p style="font-size: 12pt; color: #2C2C2C; margin: 0 0 6pt 0;">A state <i>x</i><span style="font-size: 9pt;">seed</span> of <i>X</i> <b>completion-generates</b> <i>X\u2032</i> if there exists an embedding <i>e</i> of <i>X\u2032</i> in <i>X</i> such that every embedded state is reachable via some completion:</p>
      <p style="font-size: 12pt; color: #277884; margin: 4pt 0 0 0;"><b>\u2200 s\u2032 \u2208 S\u2032 : \u2203 c \u2208 C : h(x</b><span style="font-size: 9pt;">seed</span><b>, c) = e(s\u2032)</b></p>
    </div>
    <p style="font-size: 11pt; color: #444; margin: 8pt 0 0 0;">One completion from a fixed seed state can \u201cload\u201d any state of the target machine into the ambient machine.</p>
  </div>
  <div class="col" style="display: flex; align-items: center; justify-content: center;">
    <img src="${imgPath}" style="width: 300pt;">
  </div>
</div>`);
}

// ─── Slide 6: CEK Agentic Evaluator ─────────────────────

function slide6() {
  return contentSlide('The Agentic Evaluator', `
<p style="font-size: 13pt; color: #2C2C2C; margin: 0 0 8pt 0;">Take a standard program evaluator (the <b>CEK machine</b>) and add a special primitive <span style="font-family: 'Courier New', monospace; color: #277884; font-weight: bold;">lm</span> that calls the language model.</p>
<div class="defbox">
  <p style="font-size: 13pt; color: #2C2C2C; margin: 0 0 6pt 0;"><b>Definition.</b> The <b>CEK agentic evaluator</b> observes the CEK machine only at <b>boundary states</b> \u2014 states where the next step is to evaluate <span style="font-family: 'Courier New', monospace; color: #277884;">lm</span>:</p>
  <div class="codebox" style="margin: 6pt 0 0 0;">
    <p style="font-size: 15pt; color: #2C2C2C; margin: 0; font-family: 'Courier New', monospace;"><b>let</b> y = <span style="color: #277884; font-weight: bold;">lm</span> v <b>in</b> ...</p>
  </div>
</div>
<p style="font-size: 12pt; color: #2C2C2C; margin: 10pt 0 0 0;">Between boundaries, execution is ordinary <b>deterministic evaluation</b>. The model contributes exactly one completion per transition. The evaluator itself is not a hand-written agent loop \u2014 it is the boundary-to-boundary behavior of a generic program evaluator around model calls.</p>`);
}

// ─── Slide 7: SPE State + Theorem C.5.2 ────────────────

function slide7() {
  return contentSlide('SPE States', `
<div class="defbox" style="margin-bottom: 8pt;">
  <p style="font-size: 13pt; color: #2C2C2C; margin: 0;"><b>Definition.</b> A state <i>x</i> is an <b>SPE state</b> of <i>X</i> if (<i>X</i>, <i>x</i>) completion-generates <i>X</i> itself. From one state, the model can reach an embedded copy of <i>any</i> state of the machine.</p>
</div>
<div class="thmbox" style="margin-bottom: 8pt;">
  <p style="font-size: 14pt; color: #2C2C2C; margin: 0 0 6pt 0;"><b>Theorem C.5.2.</b> The CEK agentic evaluator contains an SPE state.</p>
  <p style="font-size: 12pt; color: #2C2C2C; margin: 0;">The seed state is produced by evaluating:</p>
  <div class="codebox" style="margin: 6pt 0 0 0;">
    <p style="font-size: 17pt; color: #2C2C2C; margin: 0; font-family: 'Courier New', monospace;"><b>let</b> y = <span style="color: #277884; font-weight: bold;">lm</span> q <b>in</b> <span style="color: #E07A5F; font-weight: bold;">eval</span> y</p>
  </div>
</div>
<p style="font-size: 12pt; color: #444; margin: 0;">Ask the model for a completion <i>y</i>, then evaluate <i>y</i> as code. Whatever program the model returns becomes the next computation. Since any boundary state can be loaded by evaluating a suitable quotation, one completion from this seed reaches every state of the evaluator.</p>`);
}

// ─── Slide 8: Corollary ─────────────────────────────────

function slide8() {
  return contentSlide('Universality', `
<div class="thmbox" style="margin-top: 10pt; margin-bottom: 14pt;">
  <p style="font-size: 15pt; color: #2C2C2C; margin: 0 0 8pt 0;"><b>Corollary.</b></p>
  <p style="font-size: 14pt; color: #2C2C2C; margin: 0;">The SPE state of the agentic evaluator <b>completion-generates any computable harness</b>.</p>
</div>
<p style="font-size: 13pt; color: #2C2C2C; margin: 0 0 10pt 0;">Every agentic machine whose prompt function and harness procedure are computable can be embedded in the CEK agentic evaluator and reached from a single seed state.</p>
<p style="font-size: 13pt; color: #2C2C2C; margin: 0 0 14pt 0;">In other words: one fixed program \u2014</p>
<div class="codebox" style="margin-bottom: 14pt;">
  <p style="font-size: 17pt; color: #2C2C2C; margin: 0; font-family: 'Courier New', monospace;"><b>let</b> y = <span style="color: #277884; font-weight: bold;">lm</span> q <b>in</b> <span style="color: #E07A5F; font-weight: bold;">eval</span> y</p>
</div>
<p style="font-size: 13pt; color: #2C2C2C; margin: 0;">\u2014 allows the model to implement <b>any computable agent loop</b> by choosing the right completion. The embedding is invisible to the model (Thm C.3.3), so the model fully specifies the successor state via its program.</p>`);
}

// ─── Build Presentation ─────────────────────────────────

async function build() {
  console.log('Rasterizing diagrams...');
  await rasterize();

  const slideFns = [slide1, slide2, slide3, slide4, slide5, slide6, slide7, slide8];
  const htmlPaths = [];

  for (let i = 0; i < slideFns.length; i++) {
    const p = path.join(SLIDES, `slide${i + 1}.html`);
    fs.writeFileSync(p, slideFns[i]());
    htmlPaths.push(p);
  }

  console.log('Building PPTX...');
  const pptx = new pptxgen();
  pptx.layout = 'LAYOUT_16x9';
  pptx.author = 'Luke O\'Connor';
  pptx.title = 'Self-Programmed Execution: Theoretical Foundations';

  for (const hp of htmlPaths) {
    await html2pptx(hp, pptx);
  }

  const outPath = path.join(WS, 'spe-theory.pptx');
  await pptx.writeFile({ fileName: outPath });
  console.log(`Done: ${outPath}`);
}

build().catch(e => { console.error(e); process.exit(1); });
