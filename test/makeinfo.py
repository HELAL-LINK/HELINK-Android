"""Build a YouTube-like info JSON whose format URLs point at the local range server."""
import json
import os
import sys

media = sys.argv[1]
port = int(sys.argv[2])
out = sys.argv[3]
base = f'http://127.0.0.1:{port}/'


def size(name):
    return os.path.getsize(os.path.join(media, name))


def fmt(**kw):
    d = {
        'protocol': 'http',
        'http_headers': {'User-Agent': 'Mozilla/5.0'},
        'downloader_options': {'http_chunk_size': 1048576},
    }
    d.update(kw)
    return d


formats = [
    fmt(format_id='sb0', ext='mhtml', vcodec='none', acodec='none', protocol='mhtml',
        format_note='storyboard', url=base + 'nope.mhtml', width=160, height=90),
    fmt(format_id='140', ext='m4a', vcodec='none', acodec='mp4a.40.2', abr=129.5, tbr=129.5,
        asr=44100, filesize=size('a_aac.m4a'), format_note='medium', url=base + 'a_aac.m4a',
        language='ar', language_preference=-1, container='m4a_dash', audio_channels=2),
    fmt(format_id='251', ext='webm', vcodec='none', acodec='opus', abr=131.0, tbr=131.0,
        asr=48000, filesize=size('a_opus.webm'), format_note='medium', url=base + 'a_opus.webm',
        language='ar', language_preference=-1, container='webm_dash', audio_channels=2),
    fmt(format_id='134', ext='mp4', vcodec='avc1.4d401e', acodec='none', width=640, height=360,
        fps=30, tbr=780.0, filesize=size('v360_avc.mp4'), format_note='360p', url=base + 'v360_avc.mp4',
        dynamic_range='SDR', container='mp4_dash'),
    fmt(format_id='136', ext='mp4', vcodec='avc1.4d401f', acodec='none', width=1280, height=720,
        fps=30, tbr=3000.0, filesize=size('v720_avc.mp4'), format_note='720p', url=base + 'v720_avc.mp4',
        dynamic_range='SDR', container='mp4_dash'),
    fmt(format_id='248', ext='webm', vcodec='vp09.00.40.08', acodec='none', width=1920, height=1080,
        fps=30, tbr=1000.0, filesize=size('v1080_vp9.webm'), format_note='1080p', url=base + 'v1080_vp9.webm',
        dynamic_range='SDR', container='webm_dash'),
    fmt(format_id='18', ext='mp4', vcodec='avc1.42001E', acodec='mp4a.40.2', width=640, height=360,
        fps=30, tbr=860.0, filesize_approx=size('c360.mp4'), format_note='360p', url=base + 'c360.mp4',
        asr=44100, audio_channels=2),
]

info = {
    'id': 'TESTVID0001',
    'title': 'أغنية "تجربة" | الحلقة 1: 100% كوميدي? <نسخة> *جديدة*',
    'fulltitle': 'أغنية "تجربة" | الحلقة 1: 100% كوميدي? <نسخة> *جديدة*',
    'uploader': 'قناة التجربة',
    'channel': 'قناة التجربة',
    'duration': 8,
    'duration_string': '8',
    'webpage_url': 'https://www.youtube.com/watch?v=TESTVID0001',
    'original_url': 'https://youtu.be/TESTVID0001',
    'webpage_url_basename': 'watch',
    'webpage_url_domain': 'youtube.com',
    'extractor': sys.argv[4] if len(sys.argv) > 4 else 'generic',
    'extractor_key': (sys.argv[4] if len(sys.argv) > 4 else 'generic').capitalize(),
    'thumbnail': 'https://i.ytimg.com/vi/TESTVID0001/maxresdefault.jpg',
    'thumbnails': [
        {'url': 'https://i.ytimg.com/vi/TESTVID0001/mqdefault.jpg', 'width': 320, 'height': 180, 'id': '25'},
        {'url': 'https://i.ytimg.com/vi/TESTVID0001/hq720.jpg', 'width': 1280, 'height': 720, 'id': '35'},
    ],
    'live_status': 'not_live',
    'is_live': False,
    'was_live': False,
    'availability': 'public',
    'age_limit': 0,
    'formats': formats,
    '_type': 'video',
    'epoch': 1760000000,
    '_version': {'version': '2026.10.10.233141'},
}
with open(out, 'w', encoding='utf-8') as f:
    json.dump(info, f, ensure_ascii=False)
print('wrote', out)
