package com.xnotes.ui

import android.content.Context
import com.xnotes.R
import com.xnotes.ai.AiMsg
import com.xnotes.ai.AiText

/** Exhaustive so every message keeps a resource under R8 shrinking and new entries fail to compile. */
internal fun AiMsg.resId(): Int = when (this) {
    AiMsg.CONFIG_MODEL_KEY_REQUIRED -> R.string.ai_msg_config_model_key_required
    AiMsg.CONFIG_KEY_INVALID -> R.string.ai_msg_config_key_invalid
    AiMsg.CONFIG_SEARCH_KEY_INVALID -> R.string.ai_msg_config_search_key_invalid
    AiMsg.SEARCH_KEY_REQUIRED -> R.string.ai_msg_search_key_required
    AiMsg.CONNECTION_STOPPED -> R.string.ai_msg_connection_stopped
    AiMsg.SEARCH_HTTP_FAILED -> R.string.ai_msg_search_http_failed
    AiMsg.SEARCH_RESPONSE_TOO_LARGE -> R.string.ai_msg_search_response_too_large
    AiMsg.REQUEST_TOO_LARGE -> R.string.ai_msg_request_too_large
    AiMsg.REQUEST_HTTP_FAILED -> R.string.ai_msg_request_http_failed
    AiMsg.REQUEST_HTTP_FAILED_DETAIL -> R.string.ai_msg_request_http_failed_detail
    AiMsg.ANSWER_TOO_LONG -> R.string.ai_msg_answer_too_long
    AiMsg.PROVIDER_ERROR -> R.string.ai_msg_provider_error
    AiMsg.CONNECTION_ENDED_EARLY -> R.string.ai_msg_connection_ended_early
    AiMsg.RECOVERED_INTERRUPTED -> R.string.ai_msg_recovered_interrupted
    AiMsg.HISTORY_DIR_CREATE_FAILED -> R.string.ai_msg_history_dir_create_failed
    AiMsg.CHAT_TOO_LARGE -> R.string.ai_msg_chat_too_large
    AiMsg.HISTORY_DIR_UNREADABLE -> R.string.ai_msg_history_dir_unreadable
    AiMsg.PDF_DIR_CREATE_FAILED -> R.string.ai_msg_pdf_dir_create_failed
    AiMsg.PDF_ID_INVALID -> R.string.ai_msg_pdf_id_invalid
    AiMsg.PDF_MISSING -> R.string.ai_msg_pdf_missing
    AiMsg.PDF_LOCAL_ONLY -> R.string.ai_msg_pdf_local_only
    AiMsg.CONFIG_MAX_TOKENS_INVALID -> R.string.ai_msg_config_max_tokens_invalid
    AiMsg.CONFIG_INSTRUCTIONS_TOO_LONG -> R.string.ai_msg_config_instructions_too_long
    AiMsg.CONFIG_TOO_MANY_MODELS -> R.string.ai_msg_config_too_many_models
    AiMsg.CONFIG_THINKING_INVALID -> R.string.ai_msg_config_thinking_invalid
    AiMsg.CONFIG_TOO_MANY_PROVIDERS -> R.string.ai_msg_config_too_many_providers
    AiMsg.CONFIG_PROVIDER_PROBLEM -> R.string.ai_msg_config_provider_problem
    AiMsg.CONFIG_PROVIDERS_INVALID -> R.string.ai_msg_config_providers_invalid
    AiMsg.CONFIG_DEFAULT_MODEL_REQUIRED -> R.string.ai_msg_config_default_model_required
    AiMsg.PROVIDER_REMOVED -> R.string.ai_msg_provider_removed
    AiMsg.MODELS_HTTP_FAILED -> R.string.ai_msg_models_http_failed
    AiMsg.MODELS_RESPONSE_TOO_LARGE -> R.string.ai_msg_models_response_too_large
    AiMsg.MODELS_INVALID_REPLY -> R.string.ai_msg_models_invalid_reply
    AiMsg.PDF_DEFAULT_NAME -> R.string.ai_msg_pdf_default_name
    AiMsg.PDF_UNREADABLE -> R.string.ai_msg_pdf_unreadable
    AiMsg.PDF_CANNOT_OPEN -> R.string.ai_msg_pdf_cannot_open
    AiMsg.PDF_TOO_LARGE -> R.string.ai_msg_pdf_too_large
    AiMsg.PDF_INVALID -> R.string.ai_msg_pdf_invalid
    AiMsg.PDF_ENCRYPTED -> R.string.ai_msg_pdf_encrypted
    AiMsg.PDF_NO_PAGES -> R.string.ai_msg_pdf_no_pages
    AiMsg.PDF_PAGE_RANGE_LIMIT -> R.string.ai_msg_pdf_page_range_limit
    AiMsg.PDF_PAGE_TEXT_TOO_LONG -> R.string.ai_msg_pdf_page_text_too_long
    AiMsg.PDF_PAGE_OUT_OF_RANGE -> R.string.ai_msg_pdf_page_out_of_range
    AiMsg.PDF_TOOL_REQUEST_TOO_LONG -> R.string.ai_msg_pdf_tool_request_too_long
    AiMsg.PDF_TOOL_INVALID_REPLY -> R.string.ai_msg_pdf_tool_invalid_reply
    AiMsg.PDF_TOOL_NOTHING_READ -> R.string.ai_msg_pdf_tool_nothing_read
    AiMsg.PDF_TOOL_UNKNOWN_ATTACHMENT -> R.string.ai_msg_pdf_tool_unknown_attachment
    AiMsg.PROGRESS_READING_PAGES -> R.string.ai_msg_progress_reading_pages
    AiMsg.PROGRESS_SEARCHING_PDF -> R.string.ai_msg_progress_searching_pdf
    AiMsg.PROGRESS_VIEWING_PAGE -> R.string.ai_msg_progress_viewing_page
    AiMsg.PROGRESS_READING_PDF -> R.string.ai_msg_progress_reading_pdf
    AiMsg.PDF_TOOL_READ_RANGE_INVALID -> R.string.ai_msg_pdf_tool_read_range_invalid
    AiMsg.PDF_TOOL_SEARCH_RANGE_INVALID -> R.string.ai_msg_pdf_tool_search_range_invalid
    AiMsg.PDF_TOOL_IMAGE_LIMIT -> R.string.ai_msg_pdf_tool_image_limit
    AiMsg.PDF_TOOL_UNSUPPORTED -> R.string.ai_msg_pdf_tool_unsupported
    AiMsg.PDF_TOOL_BUDGET_EXCEEDED -> R.string.ai_msg_pdf_tool_budget_exceeded
    AiMsg.ENDPOINT_INVALID -> R.string.ai_msg_endpoint_invalid
    AiMsg.MODEL_REQUIRED -> R.string.ai_msg_model_required
    AiMsg.CHAT_TOO_LONG -> R.string.ai_msg_chat_too_long
    AiMsg.SEARCH_QUERY_LENGTH -> R.string.ai_msg_search_query_length
    AiMsg.SEARCH_RETURNED_ERROR -> R.string.ai_msg_search_returned_error
    AiMsg.PDF_SERVICE_UNAVAILABLE -> R.string.ai_msg_pdf_service_unavailable
    AiMsg.HISTORY_DAMAGED -> R.string.ai_msg_history_damaged
    AiMsg.HISTORY_READ_FAILED -> R.string.ai_msg_history_read_failed
    AiMsg.HISTORY_SAVE_FAILED -> R.string.ai_msg_history_save_failed
    AiMsg.DRAFT_SCREENSHOT_UNREADABLE -> R.string.ai_msg_draft_screenshot_unreadable
    AiMsg.PDF_UNREADABLE_CHECK -> R.string.ai_msg_pdf_unreadable_check
    AiMsg.PROVIDER_NOT_CONFIGURED -> R.string.ai_msg_provider_not_configured
    AiMsg.SEARCH_KEY_NOT_CONFIGURED -> R.string.ai_msg_search_key_not_configured
    AiMsg.QUESTION_SAVE_FAILED -> R.string.ai_msg_question_save_failed
    AiMsg.PROGRESS_PREPARING -> R.string.ai_msg_progress_preparing
    AiMsg.FETCH_URL_INVALID -> R.string.ai_msg_fetch_url_invalid
    AiMsg.FETCH_HTTP_FAILED -> R.string.ai_msg_fetch_http_failed
    AiMsg.FETCH_RESPONSE_TOO_LARGE -> R.string.ai_msg_fetch_response_too_large
    AiMsg.FETCH_RETURNED_ERROR -> R.string.ai_msg_fetch_returned_error
    AiMsg.PROGRESS_FETCHING_PAGE -> R.string.ai_msg_progress_fetching_page
    AiMsg.TOOLS_UNSUPPORTED -> R.string.ai_msg_tools_unsupported
    AiMsg.WEB_TOOL_FAILED -> R.string.ai_msg_web_tool_failed
    AiMsg.WEB_TOOL_ARGS_INVALID -> R.string.ai_msg_web_tool_args_invalid
    AiMsg.WEB_TOOL_UNKNOWN -> R.string.ai_msg_web_tool_unknown
    AiMsg.WEB_TOOL_TOO_MANY -> R.string.ai_msg_web_tool_too_many
    AiMsg.PROGRESS_SEARCHING_WEB -> R.string.ai_msg_progress_searching_web
    AiMsg.PROGRESS_GENERATING -> R.string.ai_msg_progress_generating
    AiMsg.NO_TEXT_RETURNED -> R.string.ai_msg_no_text_returned
    AiMsg.STOPPED_BY_USER -> R.string.ai_msg_stopped_by_user
    AiMsg.REQUEST_FAILED -> R.string.ai_msg_request_failed
    AiMsg.NETWORK_ERROR -> R.string.ai_msg_network_error
    AiMsg.TEMPLATE_GEN_EMPTY -> R.string.ai_msg_template_gen_empty
    AiMsg.TEMPLATE_GEN_TOO_LONG -> R.string.ai_msg_template_gen_too_long
    AiMsg.TEMPLATE_GEN_FAILED -> R.string.ai_msg_template_gen_failed
    AiMsg.CONFIG_IMAGE_PROVIDER_REQUIRED -> R.string.ai_msg_config_image_provider_required
    AiMsg.CONFIG_IMAGE_MODEL_INVALID -> R.string.ai_msg_config_image_model_invalid
    AiMsg.IMAGE_MODEL_REQUIRED -> R.string.ai_msg_image_model_required
    AiMsg.IMAGE_INVALID_REPLY -> R.string.ai_msg_image_invalid_reply
    AiMsg.IMAGE_TOO_LARGE -> R.string.ai_msg_image_too_large
    AiMsg.THINKING_UNSUPPORTED -> R.string.ai_msg_thinking_unsupported
}

/** Routes AI backend messages through Android resources so they follow the app locale. */
fun installAiText(context: Context) {
    val app = context.applicationContext
    AiText.lookup = { m, a -> app.getString(m.resId(), *a) }
}
