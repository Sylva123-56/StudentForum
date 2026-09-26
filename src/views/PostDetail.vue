<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bookmark, Flag, CheckCircle2, MessageCircle, ArrowLeft } from 'lucide-vue-next'
import { api, date, roleBadge, send, upload, type Post, type Reply } from '../api'
import { useSession } from '../store'
import RichContent from './RichContent.vue'
const route = useRoute(), router = useRouter(), session = useSession(), post = ref<Post>(), replies = ref<Reply[]>([]), content = ref(''), image = ref(''), error = ref(''), editing = ref(false), title = ref(''), body = ref(''), vote = ref<any>(), bounty = ref<any>(), chosen = ref<number[]>([]), replySort = ref('earliest'), quoteId = ref<number>(), editReplyId = ref<number>(), replyBody = ref(''), replyHistory = ref<any[]>([])
const id = Number(route.params.id)
async function load() { [post.value, replies.value] = await Promise.all([api<Post>('/posts/' + id), api<Reply[]>('/posts/' + id + '/replies?sort=' + replySort.value)]); title.value = post.value.title; body.value = post.value.content; vote.value = await api('/posts/' + id + '/vote').catch(() => null); bounty.value = await api('/posts/' + id + '/bounty').catch(() => null) }
onMounted(() => load().catch(exception => error.value = (exception as Error).message))
async function act(action: () => Promise<unknown>) { try { error.value = ''; await action(); await load(); return true } catch (exception) { error.value = (exception as Error).message; return false } }
async function addImage(event: Event) { const file = (event.target as HTMLInputElement).files?.[0]; if (file) try { image.value = (await upload(file)).path } catch (exception) { error.value = (exception as Error).message } }
async function reply() { if (await act(() => send(`/posts/${id}/replies`, 'POST', { content: content.value, imagePath: image.value || null, quoteReplyId: quoteId.value || null }))) { content.value = ''; image.value = ''; quoteId.value = undefined } }
async function favorite() { if (!session.user) return router.push('/login'); await act(() => send(`/posts/${id}/favorite`, post.value?.favorited ? 'DELETE' : 'POST')) }
async function report(type: string, targetId: number) { if (!session.user) return router.push('/login'); const reason = window.prompt('举报原因：广告垃圾 / 人身攻击 / 抄袭侵权 / 色情暴力 / 政治敏感 / 其他', '其他'); if (reason) await act(() => send('/reports', 'POST', { targetType: type, targetId, reason, description: '' })) }
async function editReply(item: Reply) { editReplyId.value = item.id; replyBody.value = item.content; replyHistory.value = await api('/replies/' + item.id + '/revisions') }
async function saveReply() { if (await act(() => send('/replies/' + editReplyId.value, 'PATCH', { content: replyBody.value }))) editReplyId.value = undefined }
async function ballot() { await act(() => send('/posts/' + id + '/vote/ballots', 'POST', { optionIds: chosen.value })) }
async function remove() { if (confirm('确定删除这篇帖子？')) { await send('/posts/' + id, 'DELETE'); router.push('/') } }
</script>
<template>
    <div v-if="post" class="detail-page">
        <RouterLink to="/" class="back-link">
            <ArrowLeft :size="17" /> 返回讨论
        </RouterLink>
        <div class="detail-head">
            <div class="post-top"><span class="type-badge" :class="post.type">{{
                post.type === 'question' ? '问答' : post.type ==='experience'?'经验':'讨论' }}</span><span v-if="post.is_solved"
                    class="mini-badge solved">已解决</span><span v-if="post.is_featured"
                    class="mini-badge featured">精华</span></div>
            <h1>{{ post.title }}</h1>
            <div class="post-meta"><span class="mini-avatar">{{ post.username?.slice(0, 1) }}</span>
                <RouterLink :to="'/users/' + post.author_id">{{ post.username }}</RouterLink><span
                    v-if="roleBadge(post.author_role, post.author_board_name)" class="role-badge"
                    :class="post.author_role">{{ roleBadge(post.author_role, post.author_board_name) }}</span><span>{{ post.board_name
                    }}</span><span>{{ date(post.created_at) }}</span><span>{{ post.view_count }} 次浏览</span>
            </div>
        </div>
        <img v-if="post.cover_path" :src="post.cover_path" class="v2-cover" alt="帖子封面" />
        <div class="article-body"><template v-if="editing"><input v-model="title" /><textarea v-model="body"
                    rows="10" /><button class="button primary"
                    @click="act(async () => { await send('/posts/' + id, 'PATCH', { title, content: body }); editing = false })">保存修改</button><button
                    class="button" @click="editing = false">取消</button></template><template v-else>
                <RichContent :content="post.content" /><img v-if="post.image_path" :src="post.image_path" alt="帖子图片" />
            </template>
        </div>
        <section v-if="vote" class="v2-vote">
            <h3>投票 · {{ vote.multiple ? '多选' : '单选' }}</h3><label v-for="option in vote.options" :key="option.id"><input
                    :type="vote.multiple ? 'checkbox' : 'radio'" name="vote" :disabled="vote.voted || vote.closed"
                    :checked="chosen.includes(option.id)"
                    @change="chosen = vote.multiple ? (chosen.includes(option.id) ? chosen.filter(value => value !== option.id) : [...chosen, option.id]) : [option.id]" />
                {{ option.label }} <span v-if="vote.visible">{{ option.ballots }} 票</span></label><button
                v-if="!vote.voted && !vote.closed && session.user" class="button primary"
                @click="ballot">提交投票</button><span v-if="vote.closed">投票已截止</span>
        </section>
        <p v-if="bounty" class="v2-social">悬赏 {{ bounty.amount }} 积分 · {{ bounty.status === 'awarded' ? '已发放' : '待采纳' }}</p><a
            v-if="post.attachment_path" :href="post.attachment_path" target="_blank" rel="noopener">下载附件</a>
        <div class="tag-cloud article-tags">
            <RouterLink v-for="tag in post.tags" :key="tag.id" :to="'/tags/' + tag.id"># {{ tag.name }}</RouterLink>
        </div>
        <div class="article-actions"><button @click="favorite">
                <Bookmark :size="18" :fill="post.favorited ? 'currentColor' : 'none'" /> {{ post.favorited ? '已收藏' : '收藏' }} {{
                    post.favorite_count }}
            </button><button @click="report('post', post.id)">
                <Flag :size="17" /> 举报
            </button><template v-if="session.user?.id === post.author_id">
                <button @click="editing = true">编辑</button>
                <RouterLink :to="'/posts/' + id + '/revisions'">编辑历史</RouterLink>
                <button @click="remove">删除</button>
            </template>
        </div>
        <section class="replies">
            <div class="section-heading">
                <h2>讨论与回答</h2><span>{{ replies.length }} 条回复</span><select v-model="replySort" @change="load">
                    <option value="earliest">最早</option>
                    <option value="latest">最新</option>
                    <option value="hot">最热</option>
                </select>
            </div>
            <div v-if="!replies.length" class="empty">还没有回复，来说说你的想法。</div>
            <article v-for="item in replies" :key="item.id" class="reply-row" :class="{ accepted: item.is_accepted }">
                <div class="reply-avatar">{{ item.username?.slice(0, 1) }}</div>
                <div class="reply-content">
                    <div class="reply-heading">
                        <RouterLink :to="'/users/' + item.author_id">{{ item.username }}</RouterLink><span
                            v-if="roleBadge(item.author_role, item.author_board_name)" class="role-badge"
                            :class="item.author_role">{{ roleBadge(item.author_role, item.author_board_name) }}</span><span>{{
                            date(item.created_at) }}</span><span v-if="item.is_accepted" class="accepted-label">
                            <CheckCircle2 :size="16" /> 最佳答案
                        </span>
                    </div>
                    <div v-if="editReplyId === item.id"><textarea v-model="replyBody" rows="5" /><button
                            @click="saveReply">保存回复</button><button @click="editReplyId = undefined">取消</button>
                        <details>
                            <summary>历史版本</summary>
                            <p v-for="version in replyHistory" :key="version.id">{{ version.content }}</p>
                        </details>
                    </div>
                    <blockquote v-if="item.quote_content">{{ item.quote_content }}</blockquote>
                    <p>{{ item.content }}</p><img v-if="item.image_path" :src="item.image_path" alt="回复图片" />
                    <div class="reply-actions"><button
                            v-if="post.type === 'question' && !post.is_solved && session.user?.id === post.author_id"
                            @click="act(() => send(`/posts/${id}/accept/${item.id}`, 'POST'))">采纳为最佳答案</button><button
                            v-if="session.user" @click="quoteId = item.id">引用回复</button><button
                            v-if="session.user?.id === item.author_id" @click="editReply(item)">编辑 / 历史</button><button
                            @click="report('reply', item.id)">举报</button><button
                            v-if="session.user?.id === item.author_id && !item.is_accepted"
                            @click="act(() => send('/replies/' + item.id, 'DELETE'))">删除</button></div>
                </div>
            </article>
        </section>
        <form v-if="session.user" class="reply-form" @submit.prevent="reply">
            <h3>参与讨论</h3>
            <p v-if="quoteId">正在引用 #{{ quoteId }} <button type="button" @click="quoteId = undefined">取消</button></p>
            <textarea v-model="content" required minlength="2" rows="5" placeholder="分享你的解答、思路或建议…" />
            <div class="reply-form-actions"><label>添加图片<input type="file" accept="image/png,image/jpeg,image/webp"
                        @change="addImage" /></label><span v-if="image">图片已添加</span><button
                    class="button primary">发布回复</button>
            </div>
        </form>
        <div v-else class="login-prompt">
            <MessageCircle :size="20" />
            <RouterLink to="/login">登录后参与讨论</RouterLink>
        </div>
        <p v-if="error" class="error">{{ error }}</p>
    </div>
    <div v-else class="empty">{{ error || '正在加载帖子…' }}</div>
</template>
