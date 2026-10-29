import client from './client';
import {
  Result,
  Blog,
  BlogCommentCreateRequest,
  BlogCommentDTO,
  BlogDetailDTO,
  BlogLikeDTO,
  BlogSaveRequest,
} from './types';

/**
 * 对应后端 BlogController（探店笔记）
 */
export const blogService = {
  /**
   * 发布探店笔记
   * POST /blogs
   */
  createBlog(payload: BlogSaveRequest) {
    return client.post<Result<number | string>>('/blogs', payload);
  },

  /**
   * 编辑我的笔记（仅作者本人）
   * PUT /blogs/{blogId}
   */
  updateBlog(blogId: number | string, payload: BlogSaveRequest) {
    return client.put<Result<null>>(`/blogs/${blogId}`, payload);
  },

  /**
   * 删除我的笔记（仅作者本人，级联删除评论）
   * DELETE /blogs/{blogId}
   */
  deleteBlog(blogId: number | string) {
    return client.delete<Result<null>>(`/blogs/${blogId}`);
  },

  /**
   * 获取笔记详情（含作者、店铺、评论）
   * GET /blogs/{blogId}
   */
  getBlogDetail(blogId: number | string) {
    return client.get<Result<BlogDetailDTO>>(`/blogs/${blogId}`);
  },

  /**
   * 按店铺查询笔记列表
   * GET /blogs/shop/{shopId}
   */
  getBlogsByShop(shopId: number | string) {
    return client.get<Result<Blog[]>>(`/blogs/shop/${shopId}`);
  },

  /**
   * 点赞（按用户幂等，重复调用不重复计数）
   * POST /blogs/{blogId}/like
   */
  likeBlog(blogId: number | string) {
    return client.post<Result<BlogLikeDTO>>(`/blogs/${blogId}/like`);
  },

  /**
   * 取消点赞（按用户幂等）
   * DELETE /blogs/{blogId}/like
   */
  unlikeBlog(blogId: number | string) {
    return client.delete<Result<BlogLikeDTO>>(`/blogs/${blogId}/like`);
  },

  /**
   * 查询当前用户的笔记列表（分页）
   * GET /blogs/mine?current=&size=
   */
  getMyBlogs(current = 1, size = 10) {
    return client.get<Result<Blog[]>>('/blogs/mine', { params: { current, size } });
  },

  /**
   * 分页查询笔记评论（含作者信息）
   * GET /blogs/{blogId}/comments?current=&size=
   */
  listComments(blogId: number | string, current = 1, size = 10) {
    return client.get<Result<BlogCommentDTO[]>>(`/blogs/${blogId}/comments`, {
      params: { current, size },
    });
  },

  /**
   * 分页查询我发表的评论
   * GET /blogs/comments/mine?current=&size=
   */
  listMyComments(current = 1, size = 10) {
    return client.get<Result<BlogCommentDTO[]>>('/blogs/comments/mine', {
      params: { current, size },
    });
  },

  /**
   * 发布评论 / 回复
   * POST /blogs/{blogId}/comments
   */
  createComment(blogId: number | string, payload: BlogCommentCreateRequest) {
    return client.post<Result<number | string>>(`/blogs/${blogId}/comments`, payload);
  },

  /**
   * 删除本人的评论（连同子回复）
   * DELETE /blogs/{blogId}/comments/{commentId}
   */
  deleteComment(blogId: number | string, commentId: number | string) {
    return client.delete<Result<void>>(`/blogs/${blogId}/comments/${commentId}`);
  },
};
