const fs = require('fs');
const path = 'C:/Users/asan6/AndroidStudioProjects/NovelScraper2/app/src/main/java/com/example/novelscraper/ScrapingTask.kt';
let content = fs.readFileSync(path, 'utf8');

let nextCandidatesIndex = content.indexOf('var nextCandidates = allLinks.filter(a => {');
let endFilterIndex = content.indexOf('});', nextCandidatesIndex);
if (nextCandidatesIndex !== -1 && endFilterIndex !== -1) {
    let before = content.substring(0, nextCandidatesIndex);
    let after = content.substring(endFilterIndex + 3);
    let newFilter = `var nextCandidates = allLinks.filter(a => {
                            var t = a.innerText.trim();
                            return t.length < 15 && (t.match(/^(?:次|Next|続く|>>|＞＞|次へ|次のページ|次ページ|次の話|下一章|下一页|下一頁)/i));
                        });`;
    content = before + newFilter + after;
}

let nextUrlAssignment = 'result.nextUrl = nextElem ? nextElem.href : \"\";';
if (content.includes(nextUrlAssignment) && !content.includes('window.book.chapter.nextId')) {
    let fallback = `result.nextUrl = nextElem ? nextElem.href : \"\";
                    if (!result.nextUrl && typeof window.book !== 'undefined' && window.book.chapter && window.book.chapter.nextId && window.book.chapter.nextId !== '-1') {
                        var baseUrl = window.book.chapterUrl || location.href;
                        result.nextUrl = baseUrl.replace('chapterId', window.book.chapter.nextId);
                    }`;
    content = content.replace(nextUrlAssignment, fallback);
}

fs.writeFileSync(path, content, 'utf8');
console.log('Update Complete.');