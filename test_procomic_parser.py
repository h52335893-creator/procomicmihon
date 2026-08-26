import re
import requests
from bs4 import BeautifulSoup

BASE = 'https://procomic.net'
HEADERS = {
    'User-Agent': 'Mozilla/5.0',
    'Referer': f'{BASE}/',
    'Accept-Language': 'ar,en;q=0.8',
}

series_url = f'{BASE}/ar/real-man-638'
chapter_url = f'{BASE}/ar/chapter/real-man-269-52254'

series_html = requests.get(series_url, headers=HEADERS, timeout=30).text
series = BeautifulSoup(series_html, 'html.parser')
chapter_links = []
for anchor in series.select("a[href*='/chapter/']"):
    href = anchor.get('href', '').strip()
    m = re.search(r'-(\d+)-\d+(?:$|[/?#])', href)
    if href and m:
        chapter_links.append((m.group(1), href, anchor.get_text(' ', strip=True)))

chapter_html = requests.get(chapter_url, headers=HEADERS, timeout=30).text
chapter = BeautifulSoup(chapter_html, 'html.parser')
script_text = '\n'.join((s.string or '') + s.get_text() for s in chapter.select('script'))
image_urls = list(dict.fromkeys(re.findall(r'https://app\.procomic\.(?:net|pro)/chapters/[^"\s\\]+\.avif', script_text)))

print('series_status', requests.get(series_url, headers=HEADERS, timeout=30).status_code)
print('series_title', series.select_one('h1').get_text(' ', strip=True) if series.select_one('h1') else '')
print('chapter_count_found', len(chapter_links))
print('real_man_chapter_found', any('/ar/chapter/real-man-269-52254' in href for _, href, _ in chapter_links))
print('chapter_status', requests.get(chapter_url, headers=HEADERS, timeout=30).status_code)
print('image_count_found', len(image_urls))
print('first_image', image_urls[0] if image_urls else '')
if image_urls:
    image_response = requests.get(image_urls[0], headers={**HEADERS, 'Referer': chapter_url}, timeout=30)
    print('first_image_status', image_response.status_code)
    print('first_image_type', image_response.headers.get('content-type', ''))
