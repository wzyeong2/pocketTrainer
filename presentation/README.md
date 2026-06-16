# 포켓 트레이너 — 발표 슬라이드

DuAI Lunchthon 2026 제출용 소개 슬라이드(3분 분량). 단일 HTML 파일로, 별도 빌드 없이 그대로 열립니다.

## 구성 (14장, WHY / WHAT / HOW / IF)

| # | 섹션 | 내용 |
|---|------|------|
| 1 | COVER | 앱 이름 · 슬로건 · GitHub QR |
| 2–4 | WHY | 러너의 페인포인트(코치 부재) → "AI로 어디까지" 실험 |
| 5–8 | WHAT | 삼성헬스→Health Connect→AI 코치 플로우 · 진단→처방→실행→평가 루프 · 라이브 코칭(음성·GPS·BLE 심박) |
| 9–12 | HOW | 듀얼 LLM(Gemini+Claude)·도메인 프롬프트·멀티모달 / 사흘 12커밋 타임라인 / "서버 0대" 스탯 |
| 13–14 | IF | 확장형 AI 제공사 · 구간별 경사 · 러닝 커뮤니티 / 클로징 |

## 조작
- 클릭(우측 70% 다음 / 좌측 30% 이전), 방향키 `←` `→`, `Space`, `Home`/`End`
- 발표는 브라우저 풀스크린(F11) 권장

## 로컬에서 보기
`index.html`을 더블클릭하거나 브라우저로 열기. (QR 이미지는 api.qrserver.com에서 받아오므로 인터넷 필요)

## Vercel 배포
```bash
# presentation 폴더만 배포할 경우
cd presentation
npx vercel --prod
```
또는 레포 전체를 Vercel에 연결하고 슬라이드 URL을 `/presentation/`으로 안내.
배포 후 나오는 URL을 제출 폼의 "소개 슬라이드" 칸에 입력.

## PDF 백업
브라우저에서 각 슬라이드를 풀스크린으로 띄운 뒤 인쇄(Ctrl+P) → PDF 저장. 정적 백업이 필요할 때만.

## 수정 포인트
- QR 대상 URL: `index.html` 내 `api.qrserver.com/...data=` 부분 (현재 GitHub 레포로 연결). 데모 영상/배포 앱 링크로 바꿀 수 있음.
- 커버 마스코트는 현재 이모지(🏃). 앱 mascot.png로 교체 가능.
- 색상 토큰은 `:root`에서 한 번에 변경 (앱 테마색 cyan #2BD4B0 / orange #FF8A3D 반영).
