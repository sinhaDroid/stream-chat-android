package com.getstream.sdk.chat.utils;

import android.app.Activity;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;

import com.getstream.sdk.chat.Chat;
import com.getstream.sdk.chat.R;
import com.getstream.sdk.chat.adapter.AttachmentListAdapter;
import com.getstream.sdk.chat.adapter.CommandMentionListItemAdapter;
import com.getstream.sdk.chat.adapter.MediaAttachmentAdapter;
import com.getstream.sdk.chat.adapter.MediaAttachmentSelectedAdapter;
import com.getstream.sdk.chat.databinding.StreamViewMessageInputBinding;
import com.getstream.sdk.chat.enums.InputType;
import com.getstream.sdk.chat.enums.MessageInputType;
import com.getstream.sdk.chat.model.AttachmentMetaData;
import com.getstream.sdk.chat.model.ModelType;
import com.getstream.sdk.chat.view.MessageInputStyle;
import com.getstream.sdk.chat.view.MessageInputView;
import com.getstream.sdk.chat.viewmodel.ChannelViewModel;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.getstream.chat.android.client.errors.ChatError;
import io.getstream.chat.android.client.models.Channel;
import io.getstream.chat.android.client.models.Command;
import io.getstream.chat.android.client.models.Member;
import io.getstream.chat.android.client.models.User;
import io.getstream.chat.android.client.utils.ProgressCallback;

public class MessageInputController {

    private static final String TAG = MessageInputController.class.getSimpleName();

    private ChannelViewModel viewModel;
    private Channel channel;
    private MessageInputStyle style;
    private MediaAttachmentAdapter mediaAttachmentAdapter;
    private MediaAttachmentSelectedAdapter selectedMediaAttachmentAdapter;
    private AttachmentListAdapter fileAttachmentAdapter;
    private AttachmentListAdapter selectedFileAttachmentAdapter;
    private CommandMentionListItemAdapter<MessageInputStyle> commandMentionListItemAdapter;
    private List<Object> commands = null;
    private Context context;
    private StreamViewMessageInputBinding binding;

    private MessageInputType messageInputType;
    private List<AttachmentMetaData> selectedAttachments = new ArrayList<>();
    private MessageInputView.AttachmentListener attachmentListener;

    private List<AttachmentMetaData> attachmentData;
    private UploadManager uploadManager;
    // region Attachment

    public MessageInputController(@NonNull Context context,
                                  @NonNull StreamViewMessageInputBinding binding,
                                  @NonNull ChannelViewModel viewModel,
                                  @NonNull MessageInputStyle style,
                                  @Nullable MessageInputView.AttachmentListener attachmentListener) {
        this.context = context;
        this.binding = binding;
        this.viewModel = viewModel;
        this.channel = viewModel.getChannel();
        this.style = style;
        this.attachmentListener = attachmentListener;
        uploadManager = new UploadManager(channel);
    }

    public List<AttachmentMetaData> getSelectedAttachments() {
        return selectedAttachments;
    }

    public boolean isUploadingFile() {
        return uploadManager.isUploadingFile();
    }

    public void setSelectedAttachments(List<AttachmentMetaData> selectedAttachments) {
        this.selectedAttachments = selectedAttachments;
    }

    public void onClickOpenBackGroundView(MessageInputType type) {

        binding.getRoot().setBackgroundResource(R.drawable.stream_round_thread_toolbar);
        binding.clTitle.setVisibility(View.VISIBLE);
        binding.btnClose.setVisibility(View.VISIBLE);

        binding.clAddFile.setVisibility(View.GONE);
        binding.clCommand.setVisibility(View.GONE);
        binding.clSelectPhoto.setVisibility(View.GONE);

        switch (type) {
            case EDIT_MESSAGE:
                break;
            case ADD_FILE:
                if (selectedAttachments != null && !selectedAttachments.isEmpty()) return;
                binding.clAddFile.setVisibility(View.VISIBLE);
                break;
            case UPLOAD_MEDIA:
            case UPLOAD_FILE:
                binding.clSelectPhoto.setVisibility(View.VISIBLE);
                configAttachmentButtonVisible(false);
                break;
            case COMMAND:
            case MENTION:
                binding.btnClose.setVisibility(View.GONE);
                binding.clCommand.setVisibility(View.VISIBLE);
                break;
        }

        binding.tvTitle.setText(type.getLabel(context));
        messageInputType = type;
        configPermissions();
    }

    public void configPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            binding.ivMediaPermission.setVisibility(View.GONE);
            binding.ivCameraPermission.setVisibility(View.GONE);
            binding.ivFilePermission.setVisibility(View.GONE);
            return;
        }

        if (PermissionChecker.isGrantedCameraPermissions(context)) {
            binding.ivMediaPermission.setVisibility(View.GONE);
            binding.ivCameraPermission.setVisibility(View.GONE);
            binding.ivFilePermission.setVisibility(View.GONE);
        } else if (PermissionChecker.isGrantedStoragePermissions(context)) {
            binding.ivMediaPermission.setVisibility(View.GONE);
            binding.ivCameraPermission.setVisibility(View.VISIBLE);
            binding.ivFilePermission.setVisibility(View.GONE);
        } else {
            binding.ivMediaPermission.setVisibility(View.VISIBLE);
            binding.ivCameraPermission.setVisibility(View.VISIBLE);
            binding.ivFilePermission.setVisibility(View.VISIBLE);
        }
    }

    public void onClickCloseBackGroundView() {
        binding.clTitle.setVisibility(View.GONE);
        binding.clAddFile.setVisibility(View.GONE);
        binding.clSelectPhoto.setVisibility(View.GONE);
        binding.clCommand.setVisibility(View.GONE);
        binding.getRoot().setBackgroundResource(0);
        messageInputType = null;
        commandMentionListItemAdapter = null;
        configAttachmentButtonVisible(true);
    }

    // endregion

    // region Upload Attachment File

    private void configSelectAttachView(boolean isMedia) {
        binding.setIsAttachFile(!isMedia);
        getAttachmentsFromLocal(isMedia);

        ((Activity) context).runOnUiThread(() -> {
            if (selectedAttachments.isEmpty()) {
                setAttachmentAdapters(isMedia);
                if (attachmentData.isEmpty()) {
                    Utils.showMessage(context, context.getResources().getString(R.string.stream_no_media_error));
                    onClickCloseBackGroundView();
                }
                binding.progressBarFileLoader.setVisibility(View.GONE);
            } else {
                showHideComposerAttachmentGalleryView(true, isMedia);
                setSelectedAttachmentAdapter(false, isMedia);
            }
        });
    }

    private void getAttachmentsFromLocal(boolean isMedia) {
        if (isMedia) {
            attachmentData = Utils.getMediaAttachments(context);
            return;
        }

        Utils.attachments = new ArrayList<>();
        attachmentData = Utils.getFileAttachments(Environment.getExternalStorageDirectory());
    }

    private void setAttachmentAdapters(boolean isMedia) {
        if (isMedia) {
            mediaAttachmentAdapter = new MediaAttachmentAdapter(context, attachmentData, position ->
                    uploadOrCancelAttachment(attachmentData.get(position), isMedia)
            );
            binding.rvMedia.setAdapter(mediaAttachmentAdapter);
        } else {
            fileAttachmentAdapter = new AttachmentListAdapter(context, attachmentData, true, true);
            binding.lvFile.setAdapter(fileAttachmentAdapter);
            binding.lvFile.setOnItemClickListener((AdapterView<?> parent, View view,
                                                   int position, long id) ->
                    uploadOrCancelAttachment(attachmentData.get(position), isMedia)
            );
        }
    }

    private void setSelectedAttachmentAdapter(boolean fromGallery, boolean isMedia) {
        if (isMedia) {
            selectedMediaAttachmentAdapter = new MediaAttachmentSelectedAdapter(context, selectedAttachments, attachment ->
                    cancelAttachment(attachment, fromGallery, isMedia));
            binding.rvComposer.setAdapter(selectedMediaAttachmentAdapter);
        } else {
            selectedFileAttachmentAdapter = new AttachmentListAdapter(context, selectedAttachments, true, false, attachment ->
                    cancelAttachment(attachment, fromGallery, isMedia));
            binding.lvComposer.setAdapter(selectedFileAttachmentAdapter);
        }
    }

    private void uploadOrCancelAttachment(AttachmentMetaData attachment,
                                          boolean isMedia) {
        if (!attachment.isSelected) {
            uploadAttachment(attachment, true, isMedia);
        } else {
            cancelAttachment(attachment, true, isMedia);
        }
    }

    public boolean isOverMaxUploadFileSize(File file, boolean showErrorToast) {
        if (file.length() > Constant.MAX_UPLOAD_FILE_SIZE) {
            if (showErrorToast)
                Utils.showMessage(context, Chat.getInstance().getStrings().get(R.string.stream_large_size_file_error));
            return true;
        }
        return false;
    }

    private void uploadAttachment(AttachmentMetaData attachment, boolean fromGallery, boolean isMedia) {
        if (isOverMaxUploadFileSize(new File(attachment.file.getPath()), true))
            return;
        attachment.isSelected = true;
        selectedAttachments.add(attachment);

        if (attachment.isUploaded())
            uploadedFileProgress(attachment);
        else
            uploadFile(attachment, fromGallery, isMedia);

        showHideComposerAttachmentGalleryView(true, isMedia);
        if (fromGallery) totalAttachmentAdapterChanged(attachment, isMedia);
        selectedAttachmentAdapterChanged(attachment, fromGallery, isMedia);
        configSendButtonEnableState();
    }

    private void uploadFile(AttachmentMetaData attachment, boolean fromGallery, boolean isMedia) {

        uploadManager.uploadFile(attachment, new ProgressCallback() {
            @Override
            public void onSuccess(@NotNull String s) {
                selectedAttachmentAdapterChanged(null, fromGallery, isMedia);
                uploadedFileProgress(attachment);
            }

            @Override
            public void onError(@NotNull ChatError chatError) {
                cancelAttachment(attachment, fromGallery, isMedia);
            }

            @Override
            public void onProgress(long l) {
                if (!attachment.isSelected) return;
                selectedAttachmentAdapterChanged(attachment, fromGallery, isMedia);
                configSendButtonEnableState();
            }
        });
    }

    private void uploadedFileProgress(AttachmentMetaData attachment) {
        if (attachmentListener != null)
            attachmentListener.onAddAttachment(attachment);
        configSendButtonEnableState();
    }

    private void cancelAttachment(AttachmentMetaData attachment, boolean fromGallery, boolean isMedia) {
        attachment.isSelected = false;
        selectedAttachments.remove(attachment);
        uploadManager.removeFromQueue(attachment);
        if (fromGallery)
            totalAttachmentAdapterChanged(null, isMedia);
        selectedAttachmentAdapterChanged(null, fromGallery, isMedia);
        configSendButtonEnableState();
        if ((selectedAttachments == null || selectedAttachments.isEmpty())
                && messageInputType == MessageInputType.EDIT_MESSAGE)
            configAttachmentButtonVisible(true);
    }

    private void configAttachmentButtonVisible(boolean visible) {
        if (!style.isShowAttachmentButton()) return;
        binding.ivOpenAttach.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void showHideComposerAttachmentGalleryView(boolean show, boolean isMedia) {
        if (isMedia)
            binding.rvComposer.setVisibility(show ? View.VISIBLE : View.GONE);
        else
            binding.lvComposer.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    public void onClickOpenSelectView(@Nullable List<AttachmentMetaData> editAttachments, boolean isMedia) {
        if (!PermissionChecker.isGrantedStoragePermissions(context)) {
            PermissionChecker.showPermissionSettingDialog(context, context.getString(R.string.stream_storage_permission_message));
            return;
        }
        initAdapter();

        if (editAttachments != null && !editAttachments.isEmpty())
            setSelectedAttachments(editAttachments);


        AsyncTask.execute(() -> configSelectAttachView(isMedia));
        if (selectedAttachments.isEmpty()) {
            binding.progressBarFileLoader.setVisibility(View.VISIBLE);
            onClickOpenBackGroundView(isMedia ? MessageInputType.UPLOAD_MEDIA : MessageInputType.UPLOAD_FILE);
        }
    }

    private void totalAttachmentAdapterChanged(@Nullable AttachmentMetaData attachment, boolean isMedia) {
        if (isMedia) {
            if (attachment == null) {
                mediaAttachmentAdapter.notifyDataSetChanged();
                return;
            }
            int index = attachmentData.indexOf(attachment);
            if (index != -1)
                mediaAttachmentAdapter.notifyItemChanged(index);
        } else
            fileAttachmentAdapter.notifyDataSetChanged();
    }

    private void selectedAttachmentAdapterChanged(@Nullable AttachmentMetaData attachment,
                                                  boolean fromGallery,
                                                  boolean isMedia) {
        if (isMedia) {
            if (selectedMediaAttachmentAdapter == null)
                setSelectedAttachmentAdapter(fromGallery, isMedia);
            if (attachment == null) {
                selectedMediaAttachmentAdapter.notifyDataSetChanged();
                return;
            }
            int index = selectedAttachments.indexOf(attachment);
            if (index != -1)
                selectedMediaAttachmentAdapter.notifyItemChanged(index);
        } else {
            if (selectedFileAttachmentAdapter == null)
                setSelectedAttachmentAdapter(fromGallery, isMedia);
            selectedFileAttachmentAdapter.notifyDataSetChanged();
        }
    }

    private void configSendButtonEnableState() {
        if (!StringUtility.isEmptyTextMessage(binding.etMessage.getText().toString())) {
            binding.setActiveMessageSend(true);
        } else {
            if (uploadManager.isUploadingFile() || selectedAttachments.isEmpty()) {
                viewModel.setInputType(InputType.DEFAULT);
                binding.setActiveMessageSend(false);
            } else {
                binding.setActiveMessageSend(true);
            }
        }
    }

    public void initSendMessage() {
        binding.etMessage.setText("");
        initAdapter();
        onClickCloseBackGroundView();
    }

    private void initAdapter() {
        selectedAttachments.clear();
        uploadManager.resetQueue();

        binding.lvComposer.removeAllViewsInLayout();
        binding.rvComposer.removeAllViewsInLayout();

        binding.lvComposer.setVisibility(View.GONE);
        binding.rvComposer.setVisibility(View.GONE);

        mediaAttachmentAdapter = null;
        selectedMediaAttachmentAdapter = null;
        fileAttachmentAdapter = null;
        selectedFileAttachmentAdapter = null;
    }
    // endregion

    // region Camera

    public void progressCapturedMedia(File file, boolean isImage) {
        AttachmentMetaData attachment = new AttachmentMetaData(file);
        attachment.file = file;
        if (isImage) {
            attachment.type = ModelType.attach_image;
        } else {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(context, Uri.fromFile(file));
            String time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long videolengh = Long.parseLong(time);
            attachment.videoLength = (int) (videolengh / 1000);
            Utils.configFileAttachment(attachment, file, ModelType.attach_file, ModelType.attach_mime_mp4);
            retriever.release();
        }
        uploadAttachment(attachment, false, true);
    }
    // endregion

    // region Cammand
    private void openCommandView() {
        onClickOpenBackGroundView(MessageInputType.COMMAND);
    }

    private void closeCommandView() {
        if (isCommandOrMention())
            onClickCloseBackGroundView();
        commands = null;
    }

    private boolean isCommandOrMention() {
        return messageInputType != null && ((messageInputType == MessageInputType.COMMAND)
                || (messageInputType == MessageInputType.MENTION));
    }

    public void checkCommand(String text) {
        if (TextUtils.isEmpty(text)
                || (!text.startsWith("/") && !text.contains("@"))) {
            closeCommandView();
        } else if (text.length() == 1) {
            onClickCommandViewOpen(text.startsWith("/"));
        } else if (text.endsWith("@")) {
            onClickCommandViewOpen(false);
        } else {
            setCommandsMentionUsers(text);
            if (!commands.isEmpty() && binding.clCommand.getVisibility() != View.VISIBLE)
                openCommandView();

            setCommandMentionListItemAdapter(text.startsWith("/"));
        }

        if (commands == null || commands.isEmpty())
            closeCommandView();

    }

    private void onClickCommandViewOpen(boolean isCommand) {
        if (isCommand) {
            setCommands("");
        } else {
            setMentionUsers("");
        }
        String title = binding.tvTitle.getContext().getResources().getString(isCommand ? R.string.stream_input_type_command : R.string.stream_input_type_auto_mention);
        binding.tvTitle.setText(title);
        binding.tvCommand.setText("");
        setCommandMentionListItemAdapter(isCommand);

        openCommandView();
        binding.lvCommand.setOnItemClickListener((AdapterView<?> adapterView, View view, int position, long l) -> {
            if (isCommand)
                binding.etMessage.setText("/" + ((Command) commands.get(position)).getName() + " ");
            else {
                String messageStr = binding.etMessage.getText().toString();
                String userName = ((User) commands.get(position)).getName();
                String converted = StringUtility.convertMentionedText(messageStr, userName);
                binding.etMessage.setText(converted);
            }
            binding.etMessage.setSelection(binding.etMessage.getText().length());
            closeCommandView();
        });
    }

    private void setCommandMentionListItemAdapter(boolean isCommand) {
        if (commandMentionListItemAdapter == null) {
            commandMentionListItemAdapter = new CommandMentionListItemAdapter(this.context, commands, style, isCommand);
            binding.lvCommand.setAdapter(commandMentionListItemAdapter);
        } else {
            commandMentionListItemAdapter.setCommand(isCommand);
            commandMentionListItemAdapter.setCommands(commands);
            commandMentionListItemAdapter.notifyDataSetChanged();
        }
    }

    private void setCommandsMentionUsers(String string) {
        if (commands == null) commands = new ArrayList<>();
        commands.clear();
        if (string.startsWith("/")) {
            List<Command> commands = channel.getConfig().getCommands();
            if (commands == null || commands.isEmpty()) return;

            String commandStr = string.replace("/", "");
            setCommands(commandStr);
            binding.tvCommand.setText(commandStr);
        } else {
            String[] names = string.split("@");
            if (names.length > 0)
                setMentionUsers(names[names.length - 1]);
        }
    }

    private void setCommands(String string) {
        if (commands == null) commands = new ArrayList<>();
        commands.clear();
        for (int i = 0; i < channel.getConfig().getCommands().size(); i++) {
            Command command = channel.getConfig().getCommands().get(i);
            if (command.getName().contains(string)) commands.add(command);
        }
    }

    private void setMentionUsers(String string) {
        if (commands == null) commands = new ArrayList<>();
        commands.clear();

        List<Member> members = channel.getMembers();
        for (int i = 0; i < members.size(); i++) {
            Member member = members.get(i);
            User user = member.getUser();
            if (user.getName().toLowerCase().contains(string.toLowerCase()))
                commands.add(user);
        }
    }
    // endregion
}
