// Preview-only stand-in for the phone side (never shipped in the app).
// Usage: preview.html?demo=home|loading|choose|error|setup|setup-error|list|list-empty|about&site=youtube|tiktok|x|instagram
//        demo=course (a whole course) | course-again (pasted again, its first lessons downloaded) | choose-inlist (a video
//        opened from a course) | update | update-dl | update-perm | update-aborted | update-error | about-update | updated
//        demo=clip | clip-home | clip-loading | clip-error | clip-nopreview (a part of a video) | wa | wa-ask | wa-empty | wa-view
//        (WhatsApp statuses)
//        &lang=en&kind=audio&theme=dark&insets=24,0,48,0&freeze=1 (the list does not move, for screenshots)&tab=downloads
window.__nzMock = function (emit) {
  const q = new URLSearchParams(location.search);
  const demo = q.get('demo') || '';
  const freeze = q.get('freeze') === '1';
  const siteParam = q.get('site') || 'youtube';
  const data = window.__nzPreviewData || {};
  let lang = q.get('lang') === 'en' ? 'en' : 'ar';
  const t2 = (ar, en) => (lang === 'en' ? en : ar);
  let seq = 0;
  const later = (ms, f) => setTimeout(f, ms);
  const posts = () => (lang === 'en' && data.postsEn ? data.postsEn : data.posts || {});
  const post = (site) => posts()[site] || posts().youtube;
  const siteOf = (text) =>
    /tiktok/.test(text) ? 'tiktok' : /(x|twitter)\.com/.test(text) ? 'x' : /instagram/.test(text) ? 'instagram' :
    /facebook|fb\.watch/.test(text) ? 'facebook' : 'youtube';
  const siteName = (s) => ({ youtube: t2('يوتيوب', 'YouTube'), tiktok: t2('تيك توك', 'TikTok'), x: t2('إكس', 'X'),
    instagram: t2('إنستجرام', 'Instagram'), facebook: t2('فيسبوك', 'Facebook') })[s] || t2('الموقع', 'the site');
  const folder = () => t2('التنزيلات ← HELINK', 'Download › HELINK');
  let current = null; // the post on screen
  const urls = { youtube: 'https://youtu.be/dQw4w9WgXcQ?si=share', tiktok: 'https://vm.tiktok.com/ZMabc123/',
    x: 'https://x.com/egfootball/status/1840000000000000000', instagram: 'https://www.instagram.com/reel/C1abcdEFGhi/' };
  const mb = (n) => (n < 10 ? n.toFixed(1) : String(Math.round(n))) + t2(' ميجابايت', ' MB');
  const speed = (n) => n.toFixed(1) + t2(' ميجابايت/ث', ' MB/s');
  const eta = (p) => (p > 80 ? t2('متبقٍ بضع ثوانٍ', 'A few seconds left') : t2('متبقٍ أقل من دقيقة', 'Less than a minute left'));
  const err = (code) => ({
    network: { code, title: t2('تعذّر الوصول إلى إنستجرام', "Can't reach Instagram"), message: t2('تأكد من اتصالك بالإنترنت، ثم حاول مرة أخرى.', 'Check your internet connection, then try again.'), retry: true, details: 'ERROR: [download] Got error: The read operation timed out' },
    login: { code, title: t2('يتطلب هذا الفيديو تسجيل الدخول', 'This video requires signing in'),
      message: t2('لا يسمح إنستجرام بتنزيل هذا الفيديو دون حساب. يُنزّل التطبيق الفيديوهات التي يستطيع أي شخص مشاهدتها دون تسجيل الدخول.', "Instagram doesn't allow downloading this video without an account. The app downloads videos that anyone can watch without signing in."),
      retry: false, action: 'newLink', details: 'ERROR: [Instagram] C1abc: This content is only available for registered users.' },
    unsupported_site: { code, title: t2('هذا الموقع غير مدعوم', "This site isn't supported"),
      message: t2('يُنزّل التطبيق من يوتيوب وفيسبوك وإنستجرام وتيك توك وإكس (تويتر). انسخ رابط فيديو من أحدها والصقه هنا.', 'The app downloads from YouTube, Facebook, Instagram, TikTok and X (Twitter). Copy a video link from one of them and paste it here.'), retry: false, action: 'newLink' },
    setup_failed: { code, title: t2('لم يكتمل التجهيز', "Setup didn't finish"),
      message: t2('غالبًا انقطع الإنترنت أثناء تنزيل أدوات التطبيق. تأكد من اتصالك بالإنترنت ومن وجود مساحة فارغة على الهاتف (نحو 200 ميجابايت)، ثم اضغط «إعادة المحاولة» ليُستأنف التنزيل من حيث توقف.', 'The internet probably dropped while the app\'s tools were downloading. Check your connection and that the phone has about 200 MB free, then tap Try again to continue where it stopped.') },
    format: { code, title: t2('هذه الجودة غير متاحة', "This quality isn't available"), message: t2('حاول مرة أخرى.', 'Try again.') },
  })[code];


  // ---------------------------------------------------------------- a course (a YouTube playlist)
  const courseTitles = () => lang === 'en'
    ? ['Welcome and what you will learn', 'Installing the tools', 'Your first project', 'Working with text', 'Colours and shapes', '[Private video]',
      'Layers and groups', 'Exporting for print', 'Exporting for social media', 'Logos: the basics', 'Practice: a shop banner', 'Mistakes to avoid',
      'Final project', 'Questions and answers']
    : ['مقدمة الكورس وما ستتعلمه', 'تثبيت البرامج المطلوبة', 'أول مشروع لك', 'التعامل مع النصوص', 'الألوان والأشكال', '[Private video]',
      'الطبقات والمجموعات', 'التصدير للطباعة', 'التصدير لمواقع التواصل', 'أساسيات تصميم الشعار', 'تطبيق عملي: بانر لمحل', 'أخطاء شائعة يجب تجنبها',
      'المشروع النهائي', 'أسئلة وأجوبة'];
  const courseDur = [312, 548, 1265, 903, 1122, 0, 987, 744, 811, 1430, 1694, 655, 2210, 1580];
  const hms = (sec) => { if (!sec) return ''; const h = Math.floor(sec / 3600), m = Math.floor(sec % 3600 / 60), x = sec % 60; return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(x).padStart(2, '0'); };
  function courseList() {
    const thumbs = [];
    for (const site of ['youtube', 'x', 'instagram', 'tiktok']) for (const it of (post(site).items || [])) if (it.meta.thumbnail) thumbs.push(it.meta.thumbnail);
    const entries = courseTitles().map((title, i) => ({ n: i + 1, id: 'VID' + String(i).padStart(8, '0'), title, channel: t2('أكاديمية التصميم', 'Design Academy'),
      duration: courseDur[i], durationText: hms(courseDur[i]), thumbnail: thumbs.length ? thumbs[i % thumbs.length] : '', available: !/^\[/.test(title),
      have: demo === 'course-again' && i < 4 ? ['video'] : undefined }));
    const total = entries.filter((e) => e.available).reduce((a, e) => a + e.duration, 0);
    const opts = [[1080, 'Full HD', t2('جودة عالية للشاشات الكبيرة', 'High quality for big screens'), 20], [720, 'HD', t2('جودة جيدة بحجم مناسب', 'Good quality, reasonable size'), 11],
      [480, '', t2('جودة متوسطة بحجم أصغر', 'Medium quality, smaller file'), 6], [360, '', t2('جودة عادية تناسب الهاتف', 'Standard quality, fine for phones'), 4]]
      .map(([qq, tag, desc, m]) => ({ key: 'v' + qq, kind: 'video', label: qq + 'p', tag, desc, recommended: qq === 720, perMinute: m * 1048576 }))
      .concat([[320, t2('أعلى جودة للصوت، والحجم أكبر', 'Best sound, larger file')], [192, t2('جودة جيدة بحجم مناسب', 'Good quality, reasonable size')], [128, t2('حجم صغير يوفّر المساحة', 'Small file that saves space')]]
        .map(([k, desc]) => ({ key: 'mp3-' + k, kind: 'audio', label: k + 'k', tag: 'MP3', desc, recommended: k === 192, perMinute: k * 1000 / 8 * 60 })));
    return { id: 'PLcourse0001', title: t2('كورس تصميم الجرافيك للمبتدئين', 'Graphic design for beginners'), channel: t2('أكاديمية التصميم', 'Design Academy'),
      url: 'https://www.youtube.com/playlist?list=PLcourse0001', entries, count: entries.filter((e) => e.available).length, duration: total,
      durationText: hms(total), more: false, options: opts };
  }
  const courseFolder = () => folder() + t2(' ← ', ' › ') + t2('كورس تصميم الجرافيك للمبتدئين', 'Graphic design for beginners');
  let curList = null;
  const listEvent = (s) => ({ seq: s, status: 'list', site: 'youtube', siteName: siteName('youtube'), list: courseList(), folderLabel: courseFolder() });

  // ---------------------------------------------------------------- HELINK's own update
  const updNotes = () => lang === 'en'
    ? ['Download a whole YouTube course or playlist at once, numbered in order.', 'HELINK tells you when a new version is out, and updates itself when you say so.']
    : ['تنزيل كورس أو قائمة تشغيل كاملة من يوتيوب مرة واحدة، مرقّمة بترتيبها.', 'يخبرك HELINK بالإصدارات الجديدة، ويُحدِّث نفسه عندما تسمح له.'];
  // about-update: "Not now" was said earlier, so the window does not open by itself.
  const upd = { state: /^(update|about-update)/.test(demo) ? 'available' : 'latest', done: 0, snoozed: demo === 'about-update', note: '' };
  if (demo === 'update-dl') { upd.state = 'downloading'; upd.done = 2.7 * 1048576; }
  if (demo === 'update-perm' || demo === 'update-aborted') upd.state = 'ready';
  if (demo === 'update-aborted') upd.note = 'aborted';
  if (demo === 'update-error') upd.state = 'error';
  let installAllowed = demo !== 'update-perm';
  const UPD_BYTES = 6.3 * 1048576;
  function updView() {
    const v = { state: upd.state, current: '1.7.1', page: 'https://github.com/HELAL-LINK/HELINK-Android' };
    if (upd.state !== 'latest' && upd.state !== '' && upd.state !== 'checking') {
      Object.assign(v, { version: '1.7.2', notes: updNotes(), sizeText: mb(6.3), snoozed: upd.snoozed });
    }
    if (upd.state === 'downloading') Object.assign(v, { doneText: mb(upd.done / 1048576), totalText: mb(6.3), percent: upd.done * 100 / UPD_BYTES });
    if (upd.state === 'ready' && upd.note) v.installNote = upd.note;
    if (upd.state === 'error') {
      v.failedIn = 'install';
      v.error = { code: 'update_network', title: t2('تعذّر تنزيل التحديث', "Couldn't download the update"),
        message: t2('انقطع الاتصال أثناء تنزيل التحديث. تأكد من اتصالك بالإنترنت ثم حاول مرة أخرى، وسيُكمل التنزيل من حيث توقف.', 'The connection dropped while the update was downloading. Check your internet connection and try again; it continues where it stopped.') };
    }
    return v;
  }
  const updEmit = () => emit('update', updView());
  let updTimer = 0;

  // ---------------------------------------------------------------- a small downloads engine
  const tasks = new Map();
  let rev = 0, idn = 0, clock = Date.now() - 3600 * 1000;
  // Without music: whether the remover is downloaded already, and the switch.
  let sepReady = q.get('sep') === 'ready', noMusicPref = q.get('nomusic') === '1';
  const SEP_MB = 63.7;
  // Like the engine: numbers inside, words (in the current language) when shown.
  function json(t, withThumb) {
    const c = Object.assign({}, t);
    const running = t.state === 'running';
    c.siteName = siteName(t.site);
    c.sizeText = t.sizeMB ? mb(t.sizeMB) : '';
    c.doneText = t.percent > 0 ? mb(t.sizeMB * t.percent / 100) : '';
    c.totalText = t.percent > 0 ? mb(t.sizeMB) : '';
    c.speedText = running && t.speed ? speed(t.speed) : '';
    c.etaText = running && t.speed ? eta(t.percent) : '';
    if (running && t.stage === 'sepmodel') {
      c.doneText = mb(SEP_MB * t.percent / 100);
      c.totalText = mb(SEP_MB);
    } else if (running && (t.stage === 'separate' || t.stage === 'sepwait' || t.stage === 'sepprep' || t.stage === 'finish' ||
        t.stage === 'merge' || t.stage === 'preparing')) {
      c.doneText = c.totalText = c.speedText = '';
      c.etaText = t.stage === 'separate' && t.percent > 3 ? eta(t.percent) : '';
    } else if (t.stage === 'clip') {
      c.doneText = c.totalText = c.speedText = c.etaText = '';
    } else if (t.stage === 'copy') {
      c.etaText = t.percent > 3 && t.percent < 97 ? eta(t.percent) : '';
    }
    if (t.errorCode) c.error = err(t.errorCode);
    if (t.state === 'done') c.result = Object.assign({}, t.result, { sizeText: mb(t.sizeMB), folderLabel: folder() });
    delete c.timer;
    delete c.errorCode;
    if (!withThumb) delete c.thumb;
    return c;
  }
  function pub(t, op, withThumb) {
    t.rev = ++rev;
    emit('task', { op: op || 'update', task: json(t, withThumb) });
  }
  function running() { let n = 0; for (const t of tasks.values()) if (t.state === 'running') n++; return n; }
  function schedule() {
    while (running() < 2) {
      let next = null;
      for (const t of tasks.values()) if (t.state === 'queued' && (!next || t.queuedAt < next.queuedAt)) next = t;
      if (!next) return;
      next.state = 'running';
      next.stage = 'preparing';
      pub(next);
      run(next);
    }
  }
  function run(t) {
    if (freeze) return;
    if (t.type === 'sep') { later(700, () => { if (t.state === 'running') removeMusic(t); }); return; }
    clearInterval(t.timer);
    t.timer = setInterval(() => {
      if (t.state !== 'running' || t.stopping) return;
      t.stage = t.clip ? 'clip' : t.kind === 'audio' ? 'audio' : t.percent < 88 ? 'video' : 'audio';
      t.partsCount = t.kind === 'audio' ? 1 : 2;
      t.partIndex = t.stage === 'audio' && t.kind !== 'audio' ? 2 : 1;
      t.percent = Math.min(100, t.percent + 3 + Math.random() * 3);
      t.speed = 1.5 + Math.random();
      if (t.percent >= 100) {
        clearInterval(t.timer);
        t.stage = t.kind === 'audio' ? 'convert' : 'merge';
        t.speed = 0;
        pub(t);
        later(900, () => {
          if (t.state !== 'running') return;
          if (t.noMusic) { removeMusic(t); return; }
          t.state = 'done';
          t.stage = '';
          t.finishedAt = Date.now();
          t.result = { path: 'content://media/external/downloads/' + (1000 + idn), mime: t.kind === 'audio' ? 'audio/mpeg' : 'video/mp4',
            name: t.title + (t.kind === 'audio' ? '.mp3' : ' - ' + t.label + '.mp4'), kind: t.kind };
          pub(t);
          schedule();
        });
        return;
      }
      pub(t);
    }, 400);
  }
  // The steps after the MP3: the remover the first time, then the removal itself.
  function removeMusic(t) {
    const step = (stage, ms, next) => {
      t.stage = stage;
      t.percent = 0;
      t.speed = stage === 'sepmodel' ? 3.2 : 0;
      pub(t);
      if (!ms) { later(700, next); return; }
      t.timer = setInterval(() => {
        if (t.state !== 'running' || t.stopping) { clearInterval(t.timer); return; }
        t.percent = Math.min(100, t.percent + 100 * 400 / ms);
        pub(t);
        if (t.percent >= 100) { clearInterval(t.timer); next(); }
      }, 400);
    };
    const separate = () => step('separate', 6000, () => {
      t.stage = t.kind === 'video' ? 'merge' : 'finish';
      pub(t);
      later(700, () => {
        if (t.state !== 'running') return;
        t.stage = 'save';
        pub(t);
        later(500, () => {
          if (t.state !== 'running') return;
          t.state = 'done';
          t.stage = '';
          t.percent = 100;
          t.finishedAt = Date.now();
          const ext = t.kind === 'video' ? 'mp4' : t.type === 'sep' ? (t.srcName.split('.').pop() || 'mp3').toLowerCase() : 'mp3';
          t.result = { path: 'content://media/external/downloads/' + (1000 + idn), mime: t.kind === 'video' ? 'video/mp4' : 'audio/mpeg',
            name: t.title + (lang === 'en' ? ' (no music).' : ' (بدون موسيقى).') + ext, kind: t.kind };
          pub(t);
          schedule();
        });
      });
    });
    const go = () => {
      for (const o of tasks.values()) {
        if (o !== t && o.state === 'running' && (o.stage === 'separate' || o.stage === 'sepmodel' || o.stage === 'sepprep')) {
          t.stage = 'sepwait';
          pub(t);
          later(800, go);
          return;
        }
      }
      if (sepReady) separate();
      else step('sepmodel', 5000, () => step('sepprep', 0, () => { sepReady = true; separate(); }));
    };
    if (t.type === 'sep' && t.stage !== 'preparing') { t.stage = 'preparing'; pub(t); later(600, go); return; }
    go();
  }
  // A file of the phone or a finished download: copied in, then its music removed.
  function addSep(fields) {
    const t = Object.assign({ id: 'd' + (++idn), type: 'sep', url: '', site: '', title: '', channel: '', durationText: '',
      kind: 'audio', key: '', label: '', sizeMB: 12, state: 'queued', stage: 'copy', percent: 0, speed: 0, noMusic: true,
      partsCount: 0, partIndex: 0, createdAt: Date.now(), queuedAt: Date.now(), thumb: '' }, fields);
    tasks.set(t.id, t);
    pub(t, 'add', true);
    if (freeze) return t;
    const tm = setInterval(() => {
      if (!tasks.has(t.id)) { clearInterval(tm); return; }
      t.percent = Math.min(100, t.percent + 25);
      t.speed = 40;
      if (t.percent >= 100) {
        clearInterval(tm);
        t.stage = '';
        t.percent = 0;
        t.speed = 0;
        pub(t);
        schedule();
        return;
      }
      pub(t);
    }, 300);
    return t;
  }
  function addTask(p, i, opt, extra) {
    const it = p.items[i];
    const t = Object.assign({
      id: 'd' + (++idn), url: urls[p.site] || urls.youtube, title: it.meta.title || 'فيديو', channel: it.meta.channel || '',
      durationText: it.meta.durationText || '', site: p.site, kind: opt.kind, key: opt.key,
      label: opt.label, sizeMB: (opt.sizeBytes || 60e6) / 1048576, state: 'queued', stage: '',
      percent: 0, speed: 0, partsCount: 0, partIndex: 0,
      createdAt: (clock += 60000), queuedAt: clock, thumb: it.meta.thumbnail || '',
    }, extra || {});
    tasks.set(t.id, t);
    return t;
  }
  // ---------------------------------------------------------------- a part of a video
  let clipSeqM = 0, clipCur = null;
  const clk = (sec) => { sec = Math.round(sec); const m = Math.floor(sec / 60), x = sec % 60; return m + ':' + String(x).padStart(2, '0'); };
  function clipEvent(s, site) {
    const p = JSON.parse(JSON.stringify(post(site)));
    for (const it of p.items) {
      it.meta.duration = 90;
      it.meta.durationText = '1:30';
      it.preview = demo === 'clip-nopreview' ? { mode: '' } : { mode: 'av', video: 'mock-clip.webm' };
    }
    return Object.assign({ seq: s, status: 'done', site: p.site, siteName: siteName(p.site) }, { items: p.items });
  }
  // ---------------------------------------------------------------- WhatsApp statuses
  const waGranted = new Set(['wa', 'wa-empty', 'wa-view'].includes(demo) ? ['wa'] : []);
  const waSaved = new Set(['s2', 's5']);
  function waItems() {
    if (demo === 'wa-empty') return [];
    const out = [];
    const kinds = ['image', 'video', 'image', 'image', 'video', 'image', 'image', 'video', 'image'];
    kinds.forEach((k, i) => {
      const id = 's' + (i + 1);
      out.push({ id, app: 'wa', kind: k, name: id + (k === 'video' ? '.mp4' : '.jpg'), size: 1, time: Date.now() - i * 3600e3,
        thumb: 'wa-' + (i + 1) + '.jpg', src: k === 'video' ? 'mock-clip.webm' : 'wa-' + (i + 1) + '.jpg', saved: waSaved.has(id) });
    });
    return out;
  }
  function optionOf(p, i, key) { return p.items[i].options.find((o) => o.key === key) || p.items[i].options[0]; }
  function info(s) { return Object.assign({ seq: s, status: 'done' }, JSON.parse(JSON.stringify(current))); }

  const api = {
    init: async () => {
      const setup = demo.startsWith('setup');
      if (demo && !api.started) { api.started = true; later(30, runDemo); }
      if (q.get('restore') === 'choose' && !current) current = JSON.parse(JSON.stringify(post(siteParam)));
      // A post on screen is written again in the current language, like the engine does.
      if (current) current = Object.assign(JSON.parse(JSON.stringify(post(current.site))), { seq: current.seq });
      if (curList) curList = Object.assign(listEvent(curList.seq), {});
      return { screen: setup ? 'setup' : 'ready', saveDirLabel: folder(), version: '1.7.1', lang, sepFiles: true,
        update: updView(), updated: demo === 'updated' ? '1.7.1' : undefined,
        missing: setup ? ['python', 'ffmpeg', 'ytdlp'] : [], busy: '', restore: curList ? { info: curList } : current ? { info: info(current.seq || 7) } : {}, parallel: 2,
        setupSizeText: setup ? mb(48) : '',
        noMusic: noMusicPref, sepSizeText: sepReady ? '' : mb(SEP_MB),
        tasks: api.tasksNow(),
        tab: q.get('tab') || undefined,
        shared: q.get('shared') || undefined,
        lastKind: q.get('kind') === 'audio' ? 'audio' : 'video', clipKind: 'video', clip: clipCur || undefined };
    },
    setLang: async (l) => { lang = l === 'en' ? 'en' : 'ar'; window.__lang = lang; },
    tasksNow: () => [...tasks.values()].sort((a, b) => b.createdAt - a.createdAt).map((t) => json(t, true)),
    tasks: async () => api.tasksNow(),
    setup: async () => {
      if (demo.startsWith('setup')) return;
      let p = 0;
      const tools = ['python', 'ffmpeg', 'ytdlp'];
      const t = setInterval(() => {
        p += 4;
        const tool = tools[Math.min(2, Math.floor(p / 34))];
        emit('setup', { phase: p > 96 ? 'verify' : tool === 'ytdlp' ? 'extract' : 'download', tool, step: tools.indexOf(tool) + 1, steps: 3, percent: p,
          doneText: mb(p * 0.48), totalText: mb(48) });
        if (p >= 100) { clearInterval(t); later(300, () => emit('setup', { phase: 'done', percent: 100 })); }
      }, 120);
    },
    paste: async () => 'https://youtu.be/dQw4w9WgXcQ?si=share',
    fetch: async (text) => {
      seq++;
      const s = seq;
      if (/dailymotion|vimeo/.test(text)) {
        emit('info', { seq: s, status: 'error', error: err('unsupported_site') });
        return s;
      }
      if (/playlist\?list=/.test(text)) {
        emit('info', { seq: s, status: 'loading', site: 'youtube', siteName: siteName('youtube'), isList: true });
        later(900, () => { current = null; curList = listEvent(s); emit('info', curList); });
        return s;
      }
      const site = siteOf(text);
      const p = post(site);
      emit('info', { seq: s, status: 'loading', site: p.site, siteName: siteName(p.site) });
      later(1100, () => {
        curList = null;
        current = Object.assign(JSON.parse(JSON.stringify(p)), { seq: s });
        const ev = info(s);
        if (/[?&]list=PL/.test(text)) ev.listUrl = 'https://www.youtube.com/playlist?list=PLcourse0001';
        emit('info', ev);
      });
      return s;
    },
    cancelFetch: async () => {},
    clipFetch: async (text) => {
      window.__lastClipFetch = text;
      const s = ++clipSeqM;
      const site = siteOf(text);
      emit('clip', { seq: s, status: 'loading', site, siteName: siteName(site) });
      if (/playlist\?list=/.test(text)) {
        later(200, () => emit('clip', { seq: s, status: 'error', site, error: { code: 'clip_list', title: t2('هذا رابط قائمة وليس فيديو', 'This is a list, not a video'),
          message: t2('لتنزيل جزء، افتح الفيديو الذي تريده وانسخ رابطه هو.', 'To download a part, open the video you want and copy its own link.'), retry: false, action: 'newLink' } }));
        return s;
      }
      if (demo === 'clip-error') {
        later(300, () => emit('clip', { seq: s, status: 'error', site: 'instagram', error: err('login') }));
        return s;
      }
      if (demo === 'clip-loading') return s;
      later(500, () => { clipCur = clipEvent(s, site); emit('clip', clipCur); });
      return s;
    },
    cancelClip: async (forget) => { window.__clipCancel = !!forget; if (forget) clipCur = null; },
    clipDownload: async (s, i, key, a, b, nm) => {
      window.__lastClip = [s, i, key, a, b, !!nm];
      if (!clipCur || b - a < 0.5) return { error: { code: 'clip_range', title: t2('اختر جزءًا أطول', 'Choose a longer part') } };
      const p = post(clipCur.site);
      const opt = optionOf(p, i, key);
      const share = (b - a) / 90;
      const t = addTask(p, i, opt, { clip: clk(a) + ' – ' + clk(b), durationText: clk(b - a), sizeMB: Math.max(0.3, (opt.sizeBytes || 60e6) / 1048576 * share / 4),
        noMusic: !!nm || undefined, title: p.items[i].meta.title });
      t.createdAt = t.queuedAt = Date.now();
      pub(t, 'add', true);
      schedule();
      return { id: t.id };
    },
    waList: async () => ({
      apps: [{ id: 'wa', installed: demo !== 'wa-none', granted: waGranted.has('wa') }, { id: 'w4b', installed: demo !== 'wa-none', granted: waGranted.has('w4b') }],
      items: waGranted.size ? waItems() : [], folderLabel: t2('التنزيلات ← HELINK ← WhatsApp', 'Download › HELINK › WhatsApp') }),
    waAsk: async (app) => { window.__waAsk = app; later(300, () => { waGranted.add(app); emit('wa', { op: 'granted', app }); }); },
    waSave: async (ids) => {
      if (typeof ids === 'string') ids = JSON.parse(ids);
      window.__waSaved = ids;
      later(400, () => { ids.forEach((id) => waSaved.add(id)); emit('wa', { op: 'saved', ids, folderLabel: t2('التنزيلات ← HELINK ← WhatsApp', 'Download › HELINK › WhatsApp') }); });
    },
    download: async (i, key, nm) => {
      window.__lastDownload = [i, key, !!nm];
      if (!current) return { error: err('format') };
      const opt = optionOf(current, i, key);
      nm = !!nm;
      noMusicPref = nm;
      for (const t of tasks.values()) {
        if (t.state !== 'done' && t.url === (urls[current.site] || urls.youtube) && t.key === key && !!t.noMusic === nm) {
          if (t.state === 'paused' || t.state === 'error') api.resume(t.id);
          return { id: t.id, existing: true };
        }
      }
      const t = addTask(current, i, opt, nm ? { noMusic: true } : null);
      t.createdAt = t.queuedAt = Date.now();
      pub(t, 'add', true);
      schedule();
      return { id: t.id };
    },
    downloadList: async (s, picks, key, nm) => {
      window.__lastList = [s, picks, key, !!nm];
      if (!curList || curList.seq !== s) return { error: err('format') };
      const l = curList.list;
      const o = l.options.find((x) => x.key === key);
      const ids = [];
      let skipped = 0;
      const width = Math.max(2, String(l.entries.length).length);
      const now = Date.now();
      for (const e of l.entries) {
        if (!picks.includes(e.n) || !e.available) continue;
        const url = 'https://www.youtube.com/watch?v=' + e.id;
        if ([...tasks.values()].some((t) => t.state !== 'done' && t.url === url && t.key === key)) { skipped++; continue; }
        const t = { id: 'd' + (++idn), url, title: e.title, channel: e.channel, durationText: e.durationText, site: 'youtube',
          kind: o.kind, key, label: o.label, sizeMB: e.duration / 60 * o.perMinute / 1048576, state: 'queued', stage: '',
          percent: 0, speed: 0, partsCount: 0, partIndex: 0, createdAt: now - ids.length, queuedAt: now + ids.length,
          thumb: e.thumbnail, list: l.title, noMusic: !!nm, base: String(e.n).padStart(width, '0') + ' - ' + e.title };
        tasks.set(t.id, t);
        pub(t, 'add', true);
        ids.push(t.id);
      }
      schedule();
      return { ids, added: ids.length, skipped, folder: courseFolder() };
    },
    update: async () => updView(),
    checkUpdate: async () => {
      upd.state = 'checking';
      updEmit();
      later(900, () => { upd.state = q.get('found') === '1' ? 'available' : 'latest'; updEmit(); });
    },
    updateNow: async () => {
      if (upd.state === 'ready') return { ok: true, ready: true };
      upd.state = 'downloading';
      upd.done = 0;
      updEmit();
      if (freeze) return { ok: true };
      clearInterval(updTimer);
      updTimer = setInterval(() => {
        upd.done = Math.min(UPD_BYTES, upd.done + 0.7 * 1048576);
        if (upd.done >= UPD_BYTES) { clearInterval(updTimer); upd.state = 'ready'; upd.note = ''; }
        updEmit();
      }, 250);
      return { ok: true };
    },
    updateLater: async () => {
      upd.snoozed = true;
      if (upd.state === 'error') upd.state = 'available'; // a failed try is forgotten
      window.__updLater = true;
      updEmit();
    },
    cancelUpdate: async () => { clearInterval(updTimer); upd.state = 'available'; updEmit(); },
    retryUpdate: async () => { upd.state = 'available'; updEmit(); },
    installUpdate: async () => {
      if (!installAllowed) return 'permission';
      upd.state = 'installing';
      updEmit();
      window.__installed = true;
      return 'started';
    },
    openInstallSettings: async () => { window.__settings = true; installAllowed = true; },
    openUpdatePage: async () => { window.__page = true; },
    pause: async (id) => {
      window.__lastPause = id;
      const t = tasks.get(id);
      if (!t) return;
      if (t.state === 'queued') { t.state = 'paused'; t.stage = ''; pub(t); return; }
      if (t.state !== 'running') return;
      t.stopping = 'pause';
      pub(t);
      later(350, () => { delete t.stopping; t.state = 'paused'; t.stage = ''; t.speed = 0; pub(t); schedule(); });
    },
    resume: async (id) => {
      window.__lastResume = id;
      const t = tasks.get(id);
      if (!t || !(t.state === 'paused' || t.state === 'error')) return;
      t.state = 'queued';
      delete t.errorCode;
      t.queuedAt = Date.now();
      pub(t);
      schedule();
    },
    deleteTask: async (id) => {
      window.__lastDelete = id;
      const t = tasks.get(id);
      if (!t) return;
      const gone = () => { clearInterval(t.timer); tasks.delete(id); emit('task', { op: 'remove', id }); schedule(); };
      if (t.state === 'running') { t.stopping = 'delete'; pub(t); later(300, gone); } else gone();
    },
    removeMusic: async (id) => {
      window.__lastRemoveMusic = id;
      const from = tasks.get(id);
      if (!from || from.state !== 'done') return { error: { code: 'file_missing', title: t2('الملف غير موجود', 'File not found') } };
      for (const x of tasks.values()) if (x.type === 'sep' && x.state !== 'done' && x.from === id) return { id: x.id, existing: true };
      const t = addSep({ from: id, title: from.title, site: from.site, kind: from.kind, thumb: from.thumb, sizeMB: from.sizeMB,
        label: from.kind === 'audio' ? 'MP3 ' + from.label : from.label, srcName: from.result.name });
      return { id: t.id };
    },
    pickFiles: async () => {
      window.__picked = (window.__picked || 0) + 1;
      later(400, () => {
        const a = addSep({ title: t2('درس الفجر', 'Morning lesson'), srcName: t2('درس الفجر.mp4', 'Morning lesson.mp4'), kind: 'video', label: 'MP4', sizeMB: 48 });
        const b = addSep({ title: t2('تلاوة', 'Recitation'), srcName: t2('تلاوة.m4a', 'Recitation.m4a'), kind: 'audio', label: 'M4A', sizeMB: 6.2 });
        window.nzFilesAdded({ ids: [a.id, b.id], skipped: q.get('skipped') === '1' ? 1 : 0, how: 'picked' });
      });
    },
    clearFinished: async () => {
      for (const t of [...tasks.values()]) if (t.state === 'done') { tasks.delete(t.id); emit('task', { op: 'remove', id: t.id }); }
    },
    openFile: async (p, m) => { window.__lastOpen = [p, m]; },
    shareFile: async (p, m, k) => { window.__lastShare = [p, m, k]; },
    copyText: async (t) => { window.__lastCopy = t; },
    contact: async (how) => { window.__lastContact = how; },
    screen: async (n) => { window.__screen = n; },
    log: async () => '20:01:02 example log line',
  };

  // A list with one download in each state, for screenshots.
  function fillList() {
    const yt = post('youtube'), tt = post('tiktok'), x = post('x'), ig = post('instagram');
    const v = (p, i, key) => optionOf(p, i, key);
    addTask(yt, 0, v(yt, 0, 'v720'), { state: 'done', percent: 100, finishedAt: clock, result: { path: 'content://media/external/downloads/1', mime: 'video/mp4', name: 'cake - 720p.mp4', kind: 'video' } });
    addTask(tt, 0, v(tt, 0, 'mp3-192'), { state: 'done', percent: 100, finishedAt: clock + 1, result: { path: 'content://media/external/downloads/2', mime: 'audio/mpeg', name: 'tiktok.mp3', kind: 'audio' } });
    addTask(ig, 1, v(ig, 1, ig.items[1].options[0].key), { state: 'error', percent: 23, errorCode: 'network' });
    addTask(x, 0, v(x, 0, x.items[0].options[0].key), { state: 'paused', percent: 62 });
    addTask(yt, 0, v(yt, 0, 'v1080'), { state: 'queued' });
    addTask(tt, 0, v(tt, 0, tt.items[0].options[0].key), { state: 'running', stage: 'video', percent: 78.4, speed: 1.8, partsCount: 1, partIndex: 1 });
    addTask(yt, 0, v(yt, 0, 'v720'), { state: 'running', stage: 'video', percent: 45.2, speed: 2.4, partsCount: 2, partIndex: 1 });
    if (q.get('sepdemo')) {
      addTask(tt, 0, v(tt, 0, 'mp3-128'), { state: 'done', noMusic: true, percent: 100, finishedAt: clock + 2, result: { path: 'content://media/external/downloads/3', mime: 'audio/mpeg', name: 'tiktok (بدون موسيقى).mp3', kind: 'audio' } });
      addTask(yt, 0, v(yt, 0, 'mp3-192'), { state: 'running', noMusic: true, stage: q.get('sepdemo'), percent: 37, speed: 3.1 });
      const f = { type: 'sep', site: '', url: '', noMusic: true, title: t2('درس الفجر', 'Morning lesson'), srcName: t2('درس الفجر.mp4', 'Morning lesson.mp4'),
        kind: 'video', label: 'MP4', sizeMB: 48, thumb: '', createdAt: (clock += 60000) };
      tasks.set('f1', Object.assign({ id: 'f1', state: 'running', stage: 'separate', percent: 64, queuedAt: clock }, f));
      tasks.set('f2', Object.assign({}, f, { id: 'f2', title: t2('تلاوة', 'Recitation'), srcName: 'x.m4a', kind: 'audio', label: 'M4A', sizeMB: 6.2,
        state: 'queued', stage: 'copy', percent: 42, speed: 35, createdAt: (clock += 60000), queuedAt: clock }));
      tasks.set('f3', Object.assign({}, f, { id: 'f3', title: t2('خطبة الجمعة', 'Friday sermon'), srcName: 'k.mp3', kind: 'audio', label: 'MP3',
        state: 'done', percent: 100, finishedAt: clock + 3, createdAt: (clock += 60000),
        result: { path: 'content://media/external/downloads/9', mime: 'audio/mpeg', name: t2('خطبة الجمعة (بدون موسيقى).mp3', 'Friday sermon (no music).mp3'), kind: 'audio' } }));
    }
    for (const t of tasks.values()) if (t.state === 'running' && !t.noMusic) run(t);
  }
  if (demo === 'list') fillList();

  function showPost(s, site) {
    const p = post(site);
    current = Object.assign(JSON.parse(JSON.stringify(p)), { seq: s });
    emit('info', { seq: s, status: 'loading', site: p.site, siteName: siteName(p.site) });
    emit('info', info(s));
  }

  function runDemo() {
    const link = document.getElementById('link');
    link.value = urls[siteParam] || urls.youtube;
    switch (demo) {
      case 'home':
        link.value = '';
        break;
      case 'loading': {
        const p = post(siteParam);
        emit('info', { seq: 99, status: 'loading', site: p.site, siteName: siteName(p.site) });
        break;
      }
      case 'choose':
        showPost(99, siteParam);
        break;
      case 'choose-inlist': {
        const p = post('youtube');
        current = Object.assign(JSON.parse(JSON.stringify(p)), { seq: 99 });
        emit('info', { seq: 99, status: 'loading', site: p.site, siteName: siteName(p.site) });
        emit('info', Object.assign(info(99), { listUrl: 'https://www.youtube.com/playlist?list=PLcourse0001' }));
        break;
      }
      case 'course':
      case 'course-again':
        link.value = 'https://www.youtube.com/playlist?list=PLcourse0001';
        emit('info', { seq: 99, status: 'loading', site: 'youtube', siteName: siteName('youtube'), isList: true });
        curList = listEvent(99);
        emit('info', curList);
        break;
      case 'update':
      case 'update-dl':
      case 'update-perm':
      case 'update-aborted':
      case 'update-error':
        link.value = '';
        updEmit();
        if (demo !== 'update') document.getElementById('upd-pill').click();
        if (demo === 'update-perm') later(50, () => document.querySelector('#upd-btns .btn.primary').click());
        break;
      case 'about-update':
        link.value = '';
        document.getElementById('about-btn').click();
        break;
      case 'clip':
      case 'clip-loading':
      case 'clip-error':
      case 'clip-nopreview':
        link.value = '';
        document.getElementById('tab-clip').click();
        document.getElementById('cpaste').click();
        break;
      case 'clip-home':
        link.value = '';
        document.getElementById('tab-clip').click();
        break;
      case 'wa':
      case 'wa-ask':
      case 'wa-empty':
      case 'wa-none':
        link.value = '';
        document.getElementById('tab-wa').click();
        break;
      case 'wa-view':
        link.value = '';
        document.getElementById('tab-wa').click();
        later(300, () => document.querySelectorAll('.wa-t')[1].click());
        break;
      case 'updated':
        link.value = '';
        break;
      case 'list':
      case 'list-empty':
        link.value = '';
        window.nzOpen('downloads');
        break;
      case 'about':
        link.value = '';
        document.getElementById('about-btn').click();
        break;
      case 'error':
        emit('info', { seq: 99, status: 'loading', site: 'instagram', siteName: siteName('instagram') });
        emit('info', { seq: 99, status: 'error', error: err('login') });
        break;
      case 'setup':
        emit('setup', { phase: 'download', tool: 'ffmpeg', step: 2, steps: 3, percent: 41, doneText: mb(20), totalText: mb(48) });
        break;
      case 'setup-error':
        emit('setup', { phase: 'download', tool: 'ffmpeg', step: 2, steps: 3, percent: 41, doneText: mb(20), totalText: mb(48) });
        emit('setup', { phase: 'error', error: err('setup_failed') });
        break;
    }
  }
  return api;
};
